/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
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
package ackcord.examplecore

import scala.language.higherKinds

import ackcord.commands.ParsedCmd
import ackcord.data.{ChannelId, GuildId}
import ackcord.{CacheSnapshot, DiscordShard}
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

class KillCmd(main: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case ParsedCmd(_, _, _, _) =>
      log.info("Received shutdown command")
      main ! DiscordShard.StopShard
      context.watch(main)
    case Terminated(_) =>
      log.info("Everything shut down")
      context.system.terminate()
  }
}
object KillCmd {
  def props(main: ActorRef): Props = Props(new KillCmd(main))
}
case class GetChannelInfo[F[_]](guildId: GuildId, senderChannelId: ChannelId, c: CacheSnapshot[F])