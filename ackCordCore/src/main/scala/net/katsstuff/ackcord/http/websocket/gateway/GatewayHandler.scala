/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2018 Katrix
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
package net.katsstuff.ackcord.http.websocket.gateway

import scala.concurrent.Future

import akka.NotUsed
import akka.actor.{ActorSystem, Props, Status}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{InvalidUpgradeResponse, ValidUpgrade, WebSocketUpgradeResponse}
import akka.pattern.pipe
import akka.stream.scaladsl._
import akka.stream.{KillSwitches, Materializer, SharedKillSwitch}
import net.katsstuff.ackcord.http.websocket.AbstractWsHandler
import net.katsstuff.ackcord.http.websocket.gateway.GatewayHandler.ConnectionDied
import net.katsstuff.ackcord.{APIMessageCacheUpdate, AckCord, Cache, CoreClientSettings}

/**
  * Responsible for normal websocket communication with Discord.
  * Some REST messages can't be sent until this has authenticated.
  * @param rawWsUri The raw uri to connect to without params
  * @param settings The settings to use.
  * @param mat The [[Materializer]] to use.
  * @param source A source of gateway messages.
  * @param sink A sink which will be sent all the dispatches of the gateway.
  */
class GatewayHandler(
    rawWsUri: Uri,
    settings: CoreClientSettings,
    source: Source[GatewayMessage[_], NotUsed],
    sink: Sink[Dispatch[_], NotUsed]
)(implicit val mat: Materializer)
    extends AbstractWsHandler[GatewayMessage[_], ResumeData] {
  import AbstractWsHandler._
  import context.dispatcher

  private implicit val system: ActorSystem      = context.system
  private var killSwitch:      SharedKillSwitch = _

  def wsUri: Uri = rawWsUri.withQuery(Query("v" -> AckCord.DiscordApiVersion, "encoding" -> "json"))

  def wsFlow: Flow[GatewayMessage[_], Dispatch[_], (Future[WebSocketUpgradeResponse], Future[Option[ResumeData]])] =
    GatewayHandlerGraphStage.flow(wsUri, settings, resume)

  override def postStop(): Unit =
    if (killSwitch != null) killSwitch.shutdown()

  def inactive: Receive = {
    case Login =>
      log.info("Logging in")
      killSwitch = KillSwitches.shared("GatewayComplete")
      val (wsUpgrade, newResumeData) =
        source.viaMat(wsFlow)(Keep.right).via(killSwitch.flow).toMat(sink)(Keep.left).run()

      newResumeData.map(ConnectionDied).pipeTo(self)

      wsUpgrade.foreach {
        case InvalidUpgradeResponse(response, cause) =>
          response.discardEntityBytes()
          killSwitch.shutdown()
          throw new IllegalStateException(s"Could not connect to gateway: $cause") //TODO
        case ValidUpgrade(response, _) =>
          log.debug("Valid login: {}", response.entity.toString)
          response.discardEntityBytes()
          self ! ValidWsUpgrade
      }

    case ValidWsUpgrade =>
      log.info("Logged in, going to Active")
      context.become(active)
  }

  def active: Receive = {
    case ConnectionDied(newResume) =>
      resume = newResume
      killSwitch.shutdown()
      killSwitch = null

      if (shuttingDown) {
        log.info("Websocket connection completed. Stopping.")
        context.stop(self)
      } else {
        log.info("Websocket connection completed. Logging in again.")
        self ! Login
        context.become(inactive)
      }
    case Status.Failure(e) =>
      log.error(e, "Websocket error")
      killSwitch.shutdown()
      killSwitch = null
      context.become(inactive)
      self ! Login
    case Logout =>
      log.info("Shutting down")
      killSwitch.shutdown()
      shuttingDown = true
  }

  override def receive: Receive = inactive
}
object GatewayHandler {

  def props(
      rawWsUri: Uri,
      settings: CoreClientSettings,
      source: Source[GatewayMessage[_], NotUsed],
      sink: Sink[Dispatch[_], NotUsed]
  )(implicit mat: Materializer): Props = Props(new GatewayHandler(rawWsUri, settings, source, sink))

  def cacheProps(wsUri: Uri, settings: CoreClientSettings, cache: Cache): Props = {
    val sink = cache.publish.contramap { (dispatch: Dispatch[_]) =>
      val event = dispatch.event.asInstanceOf[ComplexGatewayEvent[Any, Any]] //Makes stuff compile
      APIMessageCacheUpdate(event.handlerData, event.createEvent, event.cacheHandler)
    }

    Props(new GatewayHandler(wsUri, settings, cache.gatewaySubscribe, sink)(cache.mat))
  }

  case class ConnectionDied(resume: Option[ResumeData])
}
