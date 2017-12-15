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

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

/**
  * A class holding all the relevant information to create a request stream.
  * Also contains some convenience methods for common operations with requests.
  */
class RequestHelper(
    credentials: HttpCredentials,
    ratelimitActor: ActorRef,
    parallelism: Int = 4,
    bufferSize: Int = 32,
    overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
    maxAllowedWait: FiniteDuration = 2.minutes
)(implicit val system: ActorSystem, val mat: Materializer) {

  /**
    * A basic request flow which will send requests to Discord, and
    * receive responses.
    */
  def flowWithoutRateLimits[Data, Ctx]: Flow[Request[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] =
    RequestStreams.requestFlowWithoutRatelimit(credentials, parallelism, ratelimitActor)

  /**
    * A request flow which obeys route specific rate limits, but not global ones.
    */
  def flowWithRouteRatelimit[Data, Ctx]: Flow[Request[Data, Ctx], MaybeRequest[Data, Ctx], NotUsed] =
    RequestStreams.requestFlowWithRouteRatelimit[Data, Ctx](ratelimitActor, maxAllowedWait, parallelism)

  /**
    * A request flow which will send requests to Discord, and receive responses.
    * Also obeys ratelimits. If it encounters a ratelimit it will backpressure.
    */
  def flow[Data, Ctx]: Flow[Request[Data, Ctx], RequestAnswer[Data, Ctx], NotUsed] =
    RequestStreams.requestFlow(credentials, bufferSize, overflowStrategy, maxAllowedWait, parallelism, ratelimitActor)

  /**
    * A request flow which will send requests to Discord, and discard the responses.
    * Also obeys ratelimits. If it encounters a ratelimit it will backpressure.
    */
  def sinkIgnore[Data, Ctx]: Sink[Request[Data, Ctx], Future[Done]] =
    flow[Data, Ctx].toMat(Sink.ignore)(Keep.right)

  /**
    * A simple reasonable request flow using a bot token for short lived
    * streams that only returns successful responses.
    */
  def flowSuccess[Data, Ctx]: Flow[Request[Data, Ctx], (Data, Ctx), NotUsed] =
    flow[Data, Ctx].collect {
      case RequestResponse(data, ctx, _, _, _, _) => data -> ctx
    }

  /**
    * Sends a single request.
    * @param request The request to send.
    */
  def single[Data, Ctx](request: Request[Data, Ctx]): Source[RequestAnswer[Data, Ctx], NotUsed] =
    Source.single(request).via(flow)

  /**
    * Sends a single request and gets the response as a future.
    * @param request The request to send.
    */
  def singleFuture[Data, Ctx](request: Request[Data, Ctx]): Future[RequestAnswer[Data, Ctx]] =
    single(request).runWith(Sink.head)

  /**
    * Sends a single request and ignores the result.
    * @param request The request to send.
    */
  def singleIgnore[Data, Ctx](request: Request[Data, Ctx]): Unit =
    single(request).runWith(Sink.ignore)

  /**
    * A request flow that will failed requests.
    */
  def retryRequestFlow[Data, Ctx]: Flow[Request[Data, Ctx], RequestResponse[Data, Ctx], NotUsed] =
    RequestStreams.retryRequestFlow(
      credentials,
      bufferSize,
      overflowStrategy,
      maxAllowedWait,
      parallelism,
      ratelimitActor
    )

  /**
    * Sends a single request with retries if it fails.
    * @param request The request to send.
    */
  def retry[Data, Ctx](request: Request[Data, Ctx]): Source[RequestResponse[Data, Ctx], NotUsed] =
    Source.single(request).via(retryRequestFlow)

  /**
    * Sends a single request with retries if it fails, and gets the response as a future.
    * @param request The request to send.
    */
  def retryFuture[Data, Ctx](request: Request[Data, Ctx]): Future[RequestResponse[Data, Ctx]] =
    retry(request).runWith(Sink.head)

  /**
    * Sends a single request with retries if it fails, and ignores the result.
    * @param request The request to send.
    */
  def retryIgnore[Data, Ctx](request: Request[Data, Ctx]): Unit = retry(request).runWith(Sink.ignore)
}
object RequestHelper {

  def apply(
      credentials: HttpCredentials,
      parallelism: Int = 4,
      bufferSize: Int = 32,
      overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
      maxAllowedWait: FiniteDuration = 2.minutes
  )(implicit system: ActorSystem, mat: Materializer): RequestHelper =
    new RequestHelper(
      credentials,
      system.actorOf(Ratelimiter.props),
      parallelism,
      bufferSize,
      overflowStrategy,
      maxAllowedWait
    )
}
