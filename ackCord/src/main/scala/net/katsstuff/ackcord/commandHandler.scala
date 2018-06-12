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
package net.katsstuff.ackcord

import scala.language.higherKinds

import cats.{Alternative, Monad}
import net.katsstuff.ackcord.commands.{CmdCategory, CmdDescription, CmdFilter, RawCmd}
import net.katsstuff.ackcord.data.Message

/**
  * A handler for raw commands.
  */
trait RawCommandHandler[F[_]] {

  /**
    * Called whenever a command is received.
    */
  def handle(rawCmd: RawCmd[F])(implicit c: CacheSnapshot[F]): Unit
}

/**
  * A handler for raw commands that uses a [[RequestDSL]] when the commands are received.
  */
trait RawCommandHandlerDSL[F[_]] {

  /**
    * Called whenever a command is received.
    */
  def handle[G[_]](rawCmd: RawCmd[F])(
      implicit c: CacheSnapshot[F],
      DSL: RequestDSL[G],
      G: Alternative[G] with Monad[G]
  ): G[Unit]
}

/**
  * A handler for a specific command.
  *
  * @tparam A The parameter type.
  */
abstract class CommandHandler[F[_], A](
    val category: CmdCategory,
    val aliases: Seq[String],
    val filters: Seq[CmdFilter] = Nil,
    val description: Option[CmdDescription] = None
) {

  /**
    * Called whenever the command for this handler is received.
    * @param c A cache snapshot associated with the command.
    */
  def handle(msg: Message, args: A, remaining: List[String])(implicit c: CacheSnapshot[F]): Unit
}

/**
  * A handler for a specific command that uses a [[RequestDSL]] when the command is received.
  *
  * @tparam A The parameter type.
  */
abstract class CommandHandlerDSL[F[_], A](
    val category: CmdCategory,
    val aliases: Seq[String],
    val filters: Seq[CmdFilter] = Nil,
    val description: Option[CmdDescription] = None
) {

  /**
    * Called whenever the command for this handler is received.
    * @param c A cache snapshot associated with the command.
    */
  def handle[G[_]](msg: Message, args: A, remaining: List[String])(
      implicit c: CacheSnapshot[F],
      DSL: RequestDSL[G],
      G: Alternative[G] with Monad[G]
  ): G[Unit]
}