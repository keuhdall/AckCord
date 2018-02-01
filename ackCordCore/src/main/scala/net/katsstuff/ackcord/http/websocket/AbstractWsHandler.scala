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
package net.katsstuff.ackcord.http.websocket

import akka.actor.{Actor, ActorLogging, Timers}
import akka.http.scaladsl.model.Uri

/**
  * An abstract websocket handler. Handles going from inactive to active, and termination
  * @tparam WsMessageTpe The type of the websocket messages
  * @tparam Resume The resume data type
  */
abstract class AbstractWsHandler[WsMessageTpe, Resume] extends Actor with Timers with ActorLogging {

  var shuttingDown = false
  var resume: Option[Resume] = None

  /**
    * The full uri to connect to
    */
  def wsUri: Uri
}

object AbstractWsHandler {

  /**
    * Send this to an [[AbstractWsHandler]] to make it go from inactive to active
    */
  case object Login

  /**
    * Send this to an [[AbstractWsHandler]] to stop it gracefully.
    */
  case object Logout
}
