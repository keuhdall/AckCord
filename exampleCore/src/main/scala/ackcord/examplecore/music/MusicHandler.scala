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
package ackcord.examplecore.music

import scala.language.higherKinds

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._

import com.sedmelluq.discord.lavaplayer.player._
import com.sedmelluq.discord.lavaplayer.player.event._
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.{AudioPlaylist, AudioTrack, AudioTrackEndReason}

import ackcord._
import ackcord.commands.ParsedCmdFactory
import ackcord.data.raw.RawMessage
import ackcord.data.{ChannelId, GuildId, TChannel}
import ackcord.lavaplayer.LavaplayerHandler
import ackcord.lavaplayer.LavaplayerHandler._
import ackcord.syntax._
import akka.NotUsed
import akka.actor.{ActorLogging, ActorSystem, FSM, Props}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.Monad

trait CmdRegisterFunc[F[_]] {
  def apply[Mat](factory: ParsedCmdFactory[F, _, Mat]): F[Mat]
}

class MusicHandler[F[_]](
    requests: RequestHelper,
    registerCmd: CmdRegisterFunc[F],
    guildId: GuildId,
    cache: Cache
)(implicit streamable: Streamable[F], F: Monad[F])
    extends FSM[MusicHandler.MusicState, MusicHandler.StateData]
    with ActorLogging {
  import MusicHandler._
  import context.dispatcher
  import requests.mat
  implicit val system: ActorSystem = context.system

  private val msgQueue =
    Source.queue(32, OverflowStrategy.dropHead).to(requests.sinkIgnore[RawMessage, NotUsed]).run()

  private val player = MusicHandler.playerManager.createPlayer()
  player.addListener(new AudioEventSender(self))

  private val lavaplayerHandler = context.actorOf(
    LavaplayerHandler.props(player, guildId, cache, MusicHandler.UseBurstingSender),
    "LavaplayerHandler"
  )

  {
    implicit val timeout = Timeout(30.seconds)
    val cmds             = new MusicCommands[F](guildId, self)
    import cmds._
    Seq(QueueCmdFactory, StopCmdFactory, NextCmdFactory, PauseCmdFactory).foreach(registerCmd(_))
  }

  val queue: mutable.Queue[AudioTrack] = mutable.Queue.empty[AudioTrack]

  private var inVChannel             = ChannelId(0)
  private var lastTChannel: TChannel = _

  onTermination {
    case StopEvent(_, _, _) if player != null => player.destroy()
  }

  startWith(Inactive, NoData)

  when(Inactive) {
    case Event(DiscordShard.StopShard, _) =>
      lavaplayerHandler.forward(DiscordShard.StopShard)
      stop()

    case Event(APIMessage.Ready(_), _) =>
      //Startup
      stay()

    case Event(MusicReady, _) =>
      log.debug("MusicReady")
      nextTrack()
      goto(Active)

    case Event(QueueUrl(url, tChannel, vChannelId), _) if inVChannel == ChannelId(0) =>
      log.info("Received queue item in Inactive")
      lavaplayerHandler ! ConnectVChannel(vChannelId)
      lastTChannel = tChannel
      inVChannel = vChannelId

      val cmdSender = sender()
      loadItem(MusicHandler.playerManager, url).foreach { item =>
        cmdSender ! MusicHandler.CommandAck
        self ! item
      }
      stay()

    case Event(QueueUrl(url, tChannel, vChannelId), _) =>
      val cmdSender = sender()
      if (vChannelId == inVChannel) {
        loadItem(MusicHandler.playerManager, url).foreach { item =>
          cmdSender ! MusicHandler.CommandAck
          self ! item
        }
      } else {
        msgQueue
          .offer(tChannel.sendMessage("Currently joining different channel"))
          .foreach(_ => cmdSender ! MusicHandler.CommandAck)
      }
      stay()

    case Event(e: MusicHandlerEvents, _) =>
      val cmdSender = sender()
      msgQueue
        .offer(e.tChannel.sendMessage("Currently not playing music"))
        .foreach(_ => cmdSender ! MusicHandler.CommandAck)
      stay()

    case Event(track: AudioTrack, _) =>
      log.info("Received track")
      queueTrack(track)
      stay()

    case Event(playlist: AudioPlaylist, _) =>
      log.info("Received playlist")
      if (playlist.isSearchResult) {
        Option(playlist.getSelectedTrack)
          .orElse(playlist.getTracks.asScala.headOption)
          .foreach(queueTrack)
      } else queueTracks(playlist.getTracks.asScala: _*)
      stay()

    case Event(_: AudioEvent, _) => stay() //Ignore
  }

  when(Active) {
    case Event(DiscordShard.StopShard, _) =>
      lavaplayerHandler.forward(DiscordShard.StopShard)
      stop()

    case Event(StopMusic(tChannel), _) =>
      log.info("Stopped and left")

      lastTChannel = tChannel
      inVChannel = ChannelId(0)
      player.stopTrack()
      lavaplayerHandler ! DisconnectVChannel
      sender() ! MusicHandler.CommandAck

      goto(Inactive)

    case Event(QueueUrl(url, tChannel, vChannelId), _) =>
      log.info("Received queue item")
      val cmdSender = sender()
      if (vChannelId == inVChannel) {
        loadItem(MusicHandler.playerManager, url).foreach { item =>
          cmdSender ! MusicHandler.CommandAck
          self ! item
        }
      } else {
        msgQueue
          .offer(tChannel.sendMessage("Currently playing music for different channel"))
          .foreach(_ => cmdSender ! MusicHandler.CommandAck)
      }
      stay()

    case Event(NextTrack(tChannel), _) =>
      lastTChannel = tChannel
      nextTrack()
      sender() ! MusicHandler.CommandAck
      stay()

    case Event(TogglePause(tChannel), _) =>
      lastTChannel = tChannel
      player.setPaused(!player.isPaused)
      sender() ! MusicHandler.CommandAck
      stay()

    case Event(e: FriendlyException, _) => handleFriendlyException(e, None)
    case Event(track: AudioTrack, _) =>
      queueTrack(track)
      log.info("Received track")
      stay()

    case Event(playlist: AudioPlaylist, _) =>
      log.info("Received playlist")
      if (playlist.isSearchResult) {
        Option(playlist.getSelectedTrack)
          .orElse(playlist.getTracks.asScala.headOption)
          .foreach(queueTrack)
      } else queueTracks(playlist.getTracks.asScala: _*)
      stay()

    case Event(_: PlayerPauseEvent, _) =>
      log.info("Paused")
      stay()

    case Event(_: PlayerResumeEvent, _) =>
      log.info("Resumed")
      stay()

    case Event(e: TrackStartEvent, _) =>
      msgQueue.offer(lastTChannel.sendMessage(s"Playing: ${trackName(e.track)}"))
      stay()

    case Event(e: TrackEndEvent, _) =>
      val msg = e.endReason match {
        case AudioTrackEndReason.FINISHED    => s"Finished: ${trackName(e.track)}"
        case AudioTrackEndReason.LOAD_FAILED => s"Failed to load: ${trackName(e.track)}"
        case AudioTrackEndReason.STOPPED     => "Stop requested"
        case AudioTrackEndReason.REPLACED    => "Requested next track"
        case AudioTrackEndReason.CLEANUP     => "Leaking audio player"
      }

      msgQueue.offer(lastTChannel.sendMessage(msg))

      if (e.endReason.mayStartNext && queue.nonEmpty) nextTrack()
      else if (e.endReason != AudioTrackEndReason.REPLACED) self ! StopMusic(lastTChannel)

      stay()

    case Event(e: TrackExceptionEvent, _) =>
      handleFriendlyException(e.exception, Some(e.track))

    case Event(e: TrackStuckEvent, _) =>
      msgQueue.offer(lastTChannel.sendMessage(s"Track stuck: ${trackName(e.track)}. Will play next track"))
      nextTrack()
      stay()
  }

  initialize()

  def trackName(track: AudioTrack): String = track.getInfo.title

  def handleFriendlyException(e: FriendlyException, track: Option[AudioTrack]): State = {
    e.severity match {
      case FriendlyException.Severity.COMMON =>
        msgQueue.offer(lastTChannel.sendMessage(s"Encountered error: ${e.getMessage}"))
        stay()
      case FriendlyException.Severity.SUSPICIOUS =>
        msgQueue.offer(lastTChannel.sendMessage(s"Encountered error: ${e.getMessage}"))
        stay()
      case FriendlyException.Severity.FAULT =>
        msgQueue.offer(lastTChannel.sendMessage(s"Encountered internal error: ${e.getMessage}"))
        throw e
    }
  }

  def queueTrack(track: AudioTrack): Unit = {
    if (stateName == Active && player.startTrack(track, true)) {
      lavaplayerHandler ! SetPlaying(true)
    } else {
      queue.enqueue(track)
    }
  }

  def queueTracks(track: AudioTrack*): Unit = {
    queueTrack(track.head)
    queue.enqueue(track.tail: _*)
  }

  def nextTrack(): Unit = if (queue.nonEmpty) {
    player.playTrack(queue.dequeue())
    lavaplayerHandler ! SetPlaying(true)
  }
}
object MusicHandler {
  def props[F[_]](requests: RequestHelper, registerCmd: CmdRegisterFunc[F], cache: Cache)(
      implicit streamable: Streamable[F],
      F: Monad[F]
  ): GuildId => Props =
    guildId => Props(new MusicHandler(requests, registerCmd, guildId, cache))

  final val UseBurstingSender = true

  case object CommandAck

  sealed trait MusicState
  private case object Inactive extends MusicState
  private case object Active   extends MusicState

  sealed trait StateData
  case object NoData extends StateData

  sealed trait MusicHandlerEvents {
    def tChannel: TChannel
  }
  case class QueueUrl(url: String, tChannel: TChannel, vChannelId: ChannelId) extends MusicHandlerEvents
  case class StopMusic(tChannel: TChannel)                                    extends MusicHandlerEvents
  case class TogglePause(tChannel: TChannel)                                  extends MusicHandlerEvents
  case class NextTrack(tChannel: TChannel)                                    extends MusicHandlerEvents

  val playerManager: AudioPlayerManager = {
    val man = new DefaultAudioPlayerManager
    AudioSourceManagers.registerRemoteSources(man)
    man.enableGcMonitoring()
    man.getConfiguration.setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM)
    man
  }
}
