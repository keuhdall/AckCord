/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.katsstuff.ackcord.http.requests

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.pattern.{ask, AskTimeoutException}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, MergePreferred, Partition, Sink, Source, Zip}
import akka.stream.{Attributes, FlowShape, Materializer, OverflowStrategy, SourceShape}
import akka.util.Timeout
import akka.{Done, NotUsed}
import net.katsstuff.ackcord.http.requests.RESTRequests.{BaseRESTRequest, ComplexRESTRequest}
import net.katsstuff.ackcord.util.{AckCordSettings, RepeatLast}
import net.katsstuff.ackcord.{AckCord, Cache, CacheState, CacheUpdate, MiscCacheUpdate}

object RequestStreams {

  private var _uriRatelimitActor: ActorRef = _
  private def uriRateLimitActor(implicit system: ActorSystem): ActorRef = {
    if (_uriRatelimitActor == null) {
      _uriRatelimitActor = system.actorOf(Ratelimiter.props)
    }

    _uriRatelimitActor
  }

  private def findCustomHeader[H <: ModeledCustomHeader[H]](
      companion: ModeledCustomHeaderCompanion[H],
      response: HttpResponse
  ): Option[H] =
    response.headers.collectFirst {
      case h if h.name == companion.name => companion.parse(h.value).toOption
    }.flatten

  private def remainingRequests(response: HttpResponse): Int =
    findCustomHeader(`X-RateLimit-Remaining`, response).fold(-1)(_.remaining)

  private def requestsForUri(response: HttpResponse): Int =
    findCustomHeader(`X-RateLimit-Limit`, response).fold(-1)(_.limit)

  private def timeTilReset(response: HttpResponse): Long =
    findCustomHeader(`Retry-After`, response)
      .map(_.tilReset.toMillis)
      .orElse(findCustomHeader(`X-RateLimit-Reset`, response).map { h =>
        Instant.now().until(h.resetAt, ChronoUnit.MILLIS)
      })
      .getOrElse(-1)

  private def isGlobalRatelimit(response: HttpResponse): Boolean =
    findCustomHeader(`X-Ratelimit-Global`, response).fold(false)(_.isGlobal)

  private val userAgent = `User-Agent`(s"DiscordBot (https://github.com/Katrix-/AckCord, ${AckCord.Version})")

  /**
    * A basic request flow which will send requests to Discord, and
    * receive responses.
    * @param credentials The credentials to use when sending the requests.
    */
  def requestFlow[Data, Ctx](credentials: HttpCredentials, parallelism: Int = 4)(
      implicit mat: Materializer,
      system: ActorSystem
  ): Flow[RequestWrapper[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] = {
    createHttpRequestFlow[Data, Ctx](credentials)
      .via(requestHttpFlow)
      .via(requestParser(parallelism))
      .alsoTo(sendRatelimitUpdates)
  }

  /**
    * A request flow which will send requests to Discord, and receive responses.
    * Also obeys ratelimits. If it encounters a ratelimit it will backpressure.
    * @param credentials The credentials to use when sending the requests.
    */
  def requestFlowWithRatelimit[Data, Ctx](
      credentials: HttpCredentials,
      bufferSize: Int = 100,
      overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
      maxAllowedWait: FiniteDuration = 2.minutes,
      parallelism: Int = 4
  )(
      implicit mat: Materializer,
      system: ActorSystem
  ): Flow[RequestWrapper[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] = {
    val uriRatelimiterActor = uriRateLimitActor(system)

    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val in                = builder.add(Flow[RequestWrapper[Data, Ctx]])
      val buffer            = builder.add(Flow[RequestWrapper[Data, Ctx]].buffer(bufferSize, overflowStrategy))
      val globalRateLimiter = builder.add(new GlobalRatelimiter[Data, Ctx].named("GlobalRateLimiter"))
      val globalMain        = FlowShape(globalRateLimiter.in0, globalRateLimiter.out)
      val globalSecondary   = globalRateLimiter.in1
      val uri =
        builder.add(requestsWithRouteRatelimit[Data, Ctx](uriRatelimiterActor, maxAllowedWait, parallelism))

      val partition = builder.add(Partition[SentRequest[Data, Ctx]](2, {
        case _: RequestWrapper[_, _] => 0
        case _: RequestDropped[_, _] => 1
      }))
      val requests = partition.out(0).collect { case wrapper: RequestWrapper[Data, Ctx] => wrapper }
      val dropped  = partition.out(1).collect { case dropped: RequestDropped[Data, Ctx] => dropped }

      val network      = builder.add(requestFlow[Data, Ctx](credentials, parallelism))
      val answerFanout = builder.add(Broadcast[RequestAnswer[Data, Ctx]](2))
      val out          = builder.add(Merge[RequestAnswer[Data, Ctx]](2))

      val ratelimited = builder.add(Flow[RequestAnswer[Data, Ctx]].collect {
        case req @ RequestRatelimited(_, _, true, _, _) => req
      })

      // format: OFF
      in ~> buffer ~> globalMain ~> uri ~> partition
                                           requests ~> network ~> answerFanout ~> out
                                           dropped  ~>                            out
                      globalSecondary   <~ ratelimited         <~ answerFanout
      // format: ON

      FlowShape(in.in, out.out)
    }

    Flow.fromGraph(graph)
  }

  /**
    * A request flow which obeys route specific rate limits, but not global ones.
    */
  def requestsWithRouteRatelimit[Data, Ctx](
      ratelimiter: ActorRef,
      maxAllowedWait: FiniteDuration,
      parallelism: Int = 4
  )(implicit system: ActorSystem): Flow[SentRequest[Data, Ctx], SentRequest[Data, Ctx], NotUsed] = {
    implicit val triggerTimeout: Timeout = Timeout(maxAllowedWait)
    Flow[SentRequest[Data, Ctx]].mapAsyncUnordered(parallelism) {
      case wrapper @ RequestWrapper(request, _) =>
        import system.dispatcher
        val future = ratelimiter ? Ratelimiter.WantToPass(request.route.uri, wrapper)

        future.mapTo[RequestWrapper[Data, Ctx]].recover {
          case _: AskTimeoutException => wrapper.toDropped
        }
      case dropped @ RequestDropped(_, _) => Future.successful(dropped)
    }
  }.addAttributes(Attributes.name("UriRatelimiter"))

  private def createHttpRequestFlow[Data, Ctx](credentials: HttpCredentials)(
      implicit system: ActorSystem
  ): Flow[RequestWrapper[Data, Ctx], (HttpRequest, RequestWrapper[Data, Ctx]), NotUsed] = {
    val baseFlow = Flow[RequestWrapper[Data, Ctx]]

    val withLogging =
      if (AckCordSettings().LogSentREST)
        baseFlow.log(
          "Sent REST request",
          req =>
            req.request match {
              case request: ComplexRESTRequest[_, _, _, _] =>
                s"to ${request.route.uri} with method ${request.route.method} and content ${request.jsonParams.noSpaces}"
              case request => s"to ${request.route.uri} with method ${request.route.method}"
          }
        )
      else baseFlow

    withLogging
      .map {
        case wrapper @ RequestWrapper(request, _) =>
          val route = request.route
          val auth  = Authorization(credentials)

          (HttpRequest(route.method, route.uri, immutable.Seq(auth, userAgent), request.requestBody), wrapper)
      }
  }.named("CreateRequest")

  private def requestHttpFlow[Data, Ctx](
      implicit mat: Materializer,
      system: ActorSystem
  ): Flow[(HttpRequest, RequestWrapper[Data, Ctx]), (Try[HttpResponse], RequestWrapper[Data, Ctx]), NotUsed] =
    Http().superPool[RequestWrapper[Data, Ctx]]()

  private def requestParser[Data, Ctx](parallelism: Int = 4)(
      implicit mat: Materializer,
      system: ActorSystem
  ): Flow[(Try[HttpResponse], RequestWrapper[Data, Ctx]), RequestAnswer[Data, Ctx], NotUsed] =
    Flow[(Try[HttpResponse], RequestWrapper[Data, Ctx])]
      .mapAsyncUnordered(parallelism) {
        case (response, request) =>
          import system.dispatcher
          response match {
            case Success(httpResponse) =>
              val tilReset     = timeTilReset(httpResponse)
              val remainingReq = remainingRequests(httpResponse)
              val requestLimit = requestsForUri(httpResponse)

              httpResponse.status match {
                case StatusCodes.TooManyRequests =>
                  httpResponse.discardEntityBytes()
                  Future.successful(
                    RequestRatelimited(
                      request.context,
                      tilReset,
                      isGlobalRatelimit(httpResponse),
                      requestLimit,
                      request
                    )
                  )
                case StatusCodes.NoContent =>
                  httpResponse.discardEntityBytes()
                  Future.successful(
                    RequestResponseNoData(request.context, remainingReq, tilReset, requestLimit, request)
                  )
                case e if e.isFailure() =>
                  httpResponse.discardEntityBytes()
                  Future.successful(RequestError(request.context, new HttpException(e), request))
                case _ => //Should be success
                  request.request
                    .parseResponse(httpResponse.entity)
                    .map(
                      response =>
                        RequestResponse(response, request.context, remainingReq, tilReset, requestLimit, request)
                    )
                    .recover {
                      case NonFatal(e) => RequestError(request.context, e, request)
                    }
              }

            case Failure(e) => Future.successful(RequestError(request.context, e, request))
          }
      }
      .named("RequestParser")

  private def sendRatelimitUpdates[Data, Ctx]: Sink[RequestAnswer[Data, Ctx], Future[Done]] =
    Sink
      .foreach[RequestAnswer[Data, Ctx]] { answer =>
        val tilReset          = answer.tilReset
        val remainingRequests = answer.remainingRequests
        val requestLimit      = answer.uriRequestLimit
        val uri               = answer.toWrapper.request.route.uri
        if (_uriRatelimitActor != null && tilReset != -1 && remainingRequests != -1) {
          _uriRatelimitActor ! Ratelimiter.UpdateRatelimits(uri, tilReset, remainingRequests, requestLimit)
        }
      }
      .async
      .named("SendAnswersToRatelimiter")

  /**
    * A simple reasonable request flow using a bot token for short lived streams.
    * @param token The bot token.
    */
  def simpleRequestFlow[Data, Ctx](token: String)(
      implicit system: ActorSystem,
      mat: Materializer
  ): Flow[RequestWrapper[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] = {
    RequestStreams.requestFlowWithRatelimit[Data, Ctx](
      bufferSize = 32,
      overflowStrategy = OverflowStrategy.backpressure,
      maxAllowedWait = 2.minutes,
      credentials = BotAuthentication(token)
    )
  }

  /**
    * Sends a single request.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def singleRequest[Data, Ctx](
      token: String,
      wrapper: RequestWrapper[Data, Ctx]
  )(implicit system: ActorSystem, mat: Materializer): Source[RequestAnswer[Data, Ctx], NotUsed] =
    Source.single(wrapper).via(simpleRequestFlow(token))

  /**
    * Sends a single request and gets the response as a future.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def singleRequestFuture[Data, Ctx](
      token: String,
      wrapper: RequestWrapper[Data, Ctx]
  )(implicit system: ActorSystem, mat: Materializer): Future[RequestAnswer[Data, Ctx]] =
    singleRequest(token, wrapper).runWith(Sink.head)

  /**
    * Sends a single request and ignores the result.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def singleRequestIgnore[Data, Ctx](token: String, wrapper: RequestWrapper[Data, Ctx])(
      implicit system: ActorSystem,
      mat: Materializer
  ): Unit = singleRequest(token, wrapper).runWith(Sink.ignore)

  /**
    * Create a request whose answer will make a trip to the cache to get a nicer response value.
    * @param token The bot token.
    * @param restRequest The base REST request.
    * @param ctx The context to send with the request.
    */
  def requestToCache[Data, Ctx, Response, HandlerTpe](
      token: String,
      restRequest: BaseRESTRequest[Data, HandlerTpe, Response],
      ctx: Ctx,
      timeout: FiniteDuration
  )(implicit system: ActorSystem, mat: Materializer, cache: Cache): Source[Response, NotUsed] = {

    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val request     = builder.add(singleRequest(token, RequestWrapper(restRequest, ctx)))
      val broadcaster = builder.add(Broadcast[RequestAnswer[Data, Ctx]](2))

      val asCacheUpdate = builder.add(
        Flow[RequestAnswer[Data, Ctx]]
          .collect {
            case RequestResponse(data, _, _, _, _, RequestWrapper(_: BaseRESTRequest[_, _, _], _)) =>
              MiscCacheUpdate(restRequest.convertToCacheHandlerType(data), restRequest.cacheHandler)
                .asInstanceOf[CacheUpdate[Any]] //FIXME
          }
          .initialTimeout(timeout)
      )

      val repeater = builder.add(RepeatLast.flow[RequestAnswer[Data, Ctx]])

      val zipper = builder.add(Zip[RequestAnswer[Data, Ctx], (CacheUpdate[Any], CacheState)])

      val findPublished = builder.add(Flow[(RequestAnswer[Data, Ctx], (CacheUpdate[Any], CacheState))].collect {
        case (RequestResponse(data, _, _, _, _, _), (MiscCacheUpdate(data2, _), state)) if data == data2 =>
          restRequest.findData(data)(state)
      })

      // format: OFF

      request ~> broadcaster ~> asCacheUpdate ~>               cache.publish
                 broadcaster ~> repeater      ~> zipper.in0
                                                 zipper.in1 <~ cache.subscribe
                                                 zipper.out ~> findPublished

      // format: ON

      SourceShape(findPublished.out)
    }

    Source.fromGraph(graph).mapConcat(_.toList)
  }

  /**
    * A request flow that will failed requests.
    * @param token The bot token.
    */
  def retryRequestFlow[Data, Ctx](token: String)(
      implicit system: ActorSystem,
      mat: Materializer
  ): Flow[RequestWrapper[Data, Ctx], SuccessfulRequest[Data, Ctx], NotUsed] = {
    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val requestFlow = builder.add(RequestStreams.simpleRequestFlow[Data, Ctx](token))
      val allRequests = builder.add(MergePreferred[RequestAnswer[Data, Ctx]](2))

      val partitioner = builder.add(Partition[RequestAnswer[Data, Ctx]](2, {
        case _: RequestResponse[Data, Ctx] => 0
        case _: RequestFailed[Data, Ctx]   => 1
      }))

      val successful = partitioner.out(0)
      val successfulResp = builder.add(Flow[RequestAnswer[Data, Ctx]].collect {
        case response: SuccessfulRequest[Data, Ctx] => response
      })

      //Ratelimiter should take care of the ratelimits through back-pressure
      val failed = partitioner.out(1).collect {
        case failed: RequestFailed[Data, Ctx] => failed.toWrapper
      }

      // format: OFF

      requestFlow ~> allRequests ~> partitioner
      allRequests <~ requestFlow <~ failed.outlet
                                    successful ~> successfulResp

      // format: ON

      FlowShape(requestFlow.in, successfulResp.out)
    }

    Flow.fromGraph(graph)
  }

  /**
    * Sends a single request with retries if it fails.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def retryRequest[Data, Ctx](
      token: String,
      wrapper: RequestWrapper[Data, Ctx]
  )(implicit system: ActorSystem, mat: Materializer): Source[SuccessfulRequest[Data, Ctx], NotUsed] =
    Source.single(wrapper).via(retryRequestFlow(token))

  /**
    * Sends a single request with retries if it fails, and gets the response as a future.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def retryRequestFuture[Data, Ctx](
      token: String,
      wrapper: RequestWrapper[Data, Ctx]
  )(implicit system: ActorSystem, mat: Materializer): Future[SuccessfulRequest[Data, Ctx]] =
    retryRequest(token, wrapper).runWith(Sink.head)

  /**
    * Sends a single request with retries if it fails, and ignores the result.
    * @param token The bot token.
    * @param wrapper The request to send.
    */
  def retryRequestIgnore[Data, Ctx](token: String, wrapper: RequestWrapper[Data, Ctx])(
      implicit system: ActorSystem,
      mat: Materializer
  ): Unit = retryRequest(token, wrapper).runWith(Sink.ignore)
}
