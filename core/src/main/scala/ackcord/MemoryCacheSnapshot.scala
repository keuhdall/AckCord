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
package ackcord

import java.time.Instant

import ackcord.CacheSnapshot.BotUser
import ackcord.data._
import shapeless.tag.@@

/**
  * Represents the cache at some point in time
  */
case class MemoryCacheSnapshot(
    botUser: User @@ BotUser,
    dmChannelMap: SnowflakeMap[Channel, DMChannel],
    groupDmChannelMap: SnowflakeMap[Channel, GroupDMChannel],
    unavailableGuildMap: SnowflakeMap[Guild, UnavailableGuild],
    guildMap: SnowflakeMap[Guild, Guild],
    messageMap: SnowflakeMap[Channel, SnowflakeMap[Message, Message]],
    lastTypedMap: SnowflakeMap[Channel, SnowflakeMap[User, Instant]],
    userMap: SnowflakeMap[User, User],
    banMap: SnowflakeMap[Guild, SnowflakeMap[User, Ban]]
) extends CacheSnapshotId {

  override type MapType[K, V] = SnowflakeMap[K, V]

  override def getChannelMessages(channelId: ChannelId): SnowflakeMap[Message, Message] =
    messageMap.getOrElse(channelId, SnowflakeMap.empty)

  override def getChannelLastTyped(channelId: ChannelId): SnowflakeMap[User, Instant] =
    lastTypedMap.getOrElse(channelId, SnowflakeMap.empty)

  override def getGuildBans(id: GuildId): SnowflakeMap[User, Ban] =
    banMap.getOrElse(id, SnowflakeMap.empty)
}
