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
package ackcord.voice

import java.net.InetSocketAddress
import java.nio.ByteOrder

import scala.collection.mutable

import com.iwebpp.crypto.TweetNaclFast

import ackcord.AudioAPIMessage
import ackcord.data.{RawSnowflake, UserId}
import ackcord.util.AckCordVoiceSettings
import akka.actor.{ActorRef, ActorSystem, FSM, Props, Stash}
import akka.io.{IO, UdpConnected}
import akka.util.ByteString

/**
  * Handles the UDP part of voice connection
  * @param address The address to connect to
  * @param ssrc Pur ssrc
  * @param port The port to use for connection
  * @param sendSoundTo The actor to send [[AudioAPIMessage.ReceivedData]] to
  * @param serverId The server id
  * @param userId Our user Id
  */
class VoiceUDPHandler(
    address: String,
    port: Int,
    ssrc: Int,
    sendEventsTo: Option[ActorRef],
    sendSoundTo: Option[ActorRef],
    serverId: RawSnowflake,
    userId: UserId
) extends FSM[VoiceUDPHandler.State, VoiceUDPHandler.Data]
    with Stash {
  import VoiceUDPHandler._

  implicit val system: ActorSystem = context.system

  IO(UdpConnected) ! UdpConnected.Connect(self, new InetSocketAddress(address, port))

  startWith(Inactive, NoSocket)

  var sequence: Short = 0
  var timestamp       = 0

  var burstSender: ActorRef = _
  var hasSentRequest        = false

  /**
    * Sometimes we send messages too quickly, in that case we put
    * the remaining in here.
    */
  val queue: mutable.Queue[ByteString] = mutable.Queue.empty[ByteString]

  when(Inactive) {
    case Event(UdpConnected.Connected, NoSocket) =>
      unstashAll()
      stay using WithSocket(sender())

    case Event(_: DoIPDiscovery, NoSocket) =>
      //We received the request a bit early, stash it
      stash()
      stay

    case Event(ip: DoIPDiscovery, WithSocket(socket)) =>
      val payload = ByteString(ssrc).padTo(70, 0.toByte)
      socket ! UdpConnected.Send(payload)
      stay using ExpectingResponse(socket, IPDiscovery(ip.replyTo))

    case Event(UdpConnected.Received(data), ExpectingResponse(socket, IPDiscovery(replyTo))) =>
      val (addressBytes, portBytes) = data.splitAt(68)

      val address = new String(addressBytes.drop(4).toArray).trim
      val port    = portBytes.asByteBuffer.order(ByteOrder.LITTLE_ENDIAN).getShort

      replyTo ! FoundIP(address, port)
      stay using WithSocket(socket)

    case Event(StartConnection(secretKey), WithSocket(socket)) =>
      sendEventsTo.foreach(_ ! AudioAPIMessage.Ready(self, serverId, userId))
      goto(Active) using WithSecret(socket, new TweetNaclFast.SecretBox(secretKey.toArray))

    case Event(Disconnect, WithSocket(socket)) =>
      socket ! UdpConnected.Disconnect
      stay

    case Event(UdpConnected.Disconnect, WithSocket(socket)) =>
      socket ! UdpConnected.Disconnect
      stay

    case Event(UdpConnected.Disconnected, _) => stop
  }

  when(Active) {
    case Event(UdpConnected.Received(data), WithSecret(_, secret)) =>
      sendSoundTo.foreach { receiver =>
        val (rtpHeader, voice) = RTPHeader.fromBytes(data)
        if (voice.length >= 16 && rtpHeader.version != -55 && rtpHeader.version != -56) { //FIXME: These break stuff
          val decryptedData = secret.open(voice.toArray, rtpHeader.asNonce.toArray)
          if (decryptedData != null) {
            val byteStringDecrypted = ByteString(decryptedData)
            receiver ! AudioAPIMessage.ReceivedData(byteStringDecrypted, rtpHeader, serverId, userId)
          } else {
            log.error("Failed to decrypt voice data Header: {} Received voice: {}", rtpHeader, voice)
          }
        }
      }

      stay

    case Event(SendData(data), WithSecret(socket, secret)) =>
      queuePacket(data, secret, socket)
      stay

    case Event(BeginBurstMode, _) =>
      log.info("Beginning bursting mode")
      burstSender = sender()

      if (shouldSendDataRequest) {
        sendDataRequest()
      }

      stay

    case Event(StopBurstMode, _) =>
      log.info("Stopping bursting mode")
      burstSender = null
      stay

    case Event(SendDataBurst(data), WithSecret(socket, secret)) =>
      if (data.nonEmpty) {
        hasSentRequest = false
        queuePackets(data, secret, socket)
      }

      stay

    case Event(UDPAck, WithSecret(socket, _)) =>
      queue.dequeue() //Remove the one we received an ack for

      if (queue.nonEmpty) {
        socket ! UdpConnected.Send(queue.head, UDPAck)
      }

      if (shouldSendDataRequest) {
        sendDataRequest()
      }

      stay

    case Event(Disconnect, WithSecret(socket, _)) =>
      socket ! UdpConnected.Disconnect
      stay

    case Event(UdpConnected.Disconnect, WithSecret(socket, _)) =>
      socket ! UdpConnected.Disconnect
      stay

    case Event(UdpConnected.Disconnected, _) => stop
  }

  def createPayload(data: ByteString, secret: TweetNaclFast.SecretBox): ByteString = {
    val header = RTPHeader(sequence, timestamp, ssrc)
    sequence = (sequence + 1).toShort
    timestamp += FrameSize
    val encrypted = secret.box(data.toArray, header.asNonce.toArray)
    header.byteString ++ ByteString(encrypted)
  }

  def queuePacket(data: ByteString, secret: TweetNaclFast.SecretBox, socket: ActorRef): Unit = {
    val payload = createPayload(data, secret)
    queue.enqueue(payload) //This is removed as soon as we receive the ack if it's the only thing in the queue

    val maxSize = AckCordVoiceSettings().UDPMaxPacketsBeforeDrop

    if (queue.lengthCompare(1) == 0) {
      socket ! UdpConnected.Send(payload, UDPAck)
    } else if (queue.lengthCompare(maxSize) > 0) {
      val toDrop = queue.size - maxSize
      log.warning("Dropped {} packets", toDrop)

      for (_ <- 0 until toDrop) queue.dequeue()
    }
  }

  def queuePackets(data: Seq[ByteString], secret: TweetNaclFast.SecretBox, socket: ActorRef): Unit = {
    queuePacket(data.head, secret, socket)

    val maxSize = AckCordVoiceSettings().UDPMaxPacketsBeforeDrop

    val combinedSize = queue.size + data.tail.size
    val withDropped = if (combinedSize > maxSize) {
      val toDrop = combinedSize - maxSize
      log.warning("Dropped {} packets", toDrop)

      data.tail.drop(toDrop)
    } else data.tail

    withDropped.foreach(data => queue.enqueue(createPayload(data, secret)))
  }

  def shouldSendDataRequest: Boolean =
    burstSender != null && !hasSentRequest && queue.lengthCompare(AckCordVoiceSettings().UDPSendRequestAmount) <= 0

  def sendDataRequest(): Unit = {
    burstSender ! DataRequest(AckCordVoiceSettings().UDPMaxBurstAmount - queue.size)
    hasSentRequest = true
  }

  initialize()
}
object VoiceUDPHandler {
  def props(
      address: String,
      port: Int,
      ssrc: Int,
      sendTo: Option[ActorRef],
      sendSoundTo: Option[ActorRef],
      serverId: RawSnowflake,
      userId: UserId
  ): Props = Props(new VoiceUDPHandler(address, port, ssrc, sendTo, sendSoundTo, serverId, userId))

  sealed trait State
  case object Inactive extends State
  case object Active   extends State

  sealed trait Data
  case object NoSocket                                                     extends Data
  case class WithSocket(socket: ActorRef)                                  extends Data
  case class WithSecret(socket: ActorRef, secret: TweetNaclFast.SecretBox) extends Data

  /**
    * Used if we're expecting some response from another actor. Currently
    * only used for IP discovery
    * @param socket The socket actor
    * @param expectedTpe The type of response we're expecting
    */
  private case class ExpectingResponse(socket: ActorRef, expectedTpe: ExpectedResponseType) extends Data

  private case class UDPAck(sequence: Short)

  sealed private trait ExpectedResponseType
  private case class IPDiscovery(replyTo: ActorRef) extends ExpectedResponseType

  /**
    * Send this when this is in [[Inactive]] to do IP discovery.
    * @param replyTo [[FoundIP]] is sent to this actor when the ip is found
    */
  case class DoIPDiscovery(replyTo: ActorRef)

  /**
    * Sent to an actor after [[DoIPDiscovery]]
    * @param address Our address or ip address
    * @param port Our port
    */
  case class FoundIP(address: String, port: Int)

  /**
    * Send to this to start a connection, and go from [[Inactive]] to [[Active]]
    * @param secretKey The secret key to use for encrypting
    *                  and decrypting voice data.
    */
  case class StartConnection(secretKey: ByteString)

  /**
    * Send to this to start disconnecting.
    */
  case object Disconnect

  /**
    * Send to this to send some data
    * @param data The data to send
    */
  case class SendData(data: ByteString)

  /**
    * Begins bursting mode. After sending this to the [[VoiceUDPHandler]], you
    * should receive a [[DataRequest]] once there is room for more packets to
    * be sent.
    */
  case object BeginBurstMode

  /**
    * Stop bursting mode. All this does is top sending [[DataRequest]]s.
    */
  case object StopBurstMode

  /**
    * Send a burst of to the [[VoiceUDPHandler]] at the same time.
    * While this can be used just like [[SendData]], it's best used
    * as a response to [[DataRequest]].
    * @param data The data to send. The size should be equal to
    *             [[DataRequest.numOfPackets]].
    */
  case class SendDataBurst(data: Seq[ByteString])

  /**
    * Sent to the sender of [[BeginBurstMode]] once there is room for more
    * packets.
    * @param numOfPackets The amount of packets to send.
    */
  case class DataRequest(numOfPackets: Int)

  private val nonceLastPart = ByteString.fromArray(new Array(12))

  val silence = ByteString(0xF8, 0xFF, 0xFE)

  val SampleRate = 48000
  val FrameSize  = 960
  val FrameTime  = 20

  /**
    * Represents the RTP header used for sending and receiving voice data
    * @param tpe The type to use. Should be `0x80`
    * @param version The version to use. Should be `0x78`
    * @param sequence The sequence
    * @param timestamp Timestamp
    * @param ssrc SSRC of sender
    */
  case class RTPHeader(tpe: Byte, version: Byte, sequence: Short, timestamp: Int, ssrc: Int) {
    lazy val byteString: ByteString = {
      val builder                   = ByteString.newBuilder
      implicit val order: ByteOrder = ByteOrder.BIG_ENDIAN
      builder.putByte(tpe)
      builder.putByte(version)
      builder.putShort(sequence)
      builder.putInt(timestamp)
      builder.putInt(ssrc)

      builder.result()
    }

    def asNonce: ByteString = byteString ++ nonceLastPart
  }
  object RTPHeader {

    /**
      * Deserialize an [[RTPHeader]]
      */
    def fromBytes(bytes: ByteString): (RTPHeader, ByteString) = {
      val (header, extra) = bytes.splitAt(12)

      val buffer    = header.asByteBuffer.order(ByteOrder.BIG_ENDIAN)
      val tpe       = buffer.get()
      val version   = buffer.get()
      val sequence  = buffer.getShort()
      val timestamp = buffer.getInt()
      val ssrc      = buffer.getInt()

      //https://tools.ietf.org/html/rfc5285#section-4.2
      //I have no idea what this does
      if (tpe == 0x90 && extra(0) == 0xBE && extra(1) == 0xDE) {
        val hlen = extra(2) << 8 | extra(3)
        var i    = 4

        while (i < hlen + 4) {
          val b   = extra(i)
          val len = (b & 0x0F) + 1
          i += (len + 1)
        }
        while (extra(i) == 0) i += 1

        val newAudio = extra.drop(i)
        (RTPHeader(tpe, version, sequence, timestamp, ssrc), newAudio)
      } else (RTPHeader(tpe, version, sequence, timestamp, ssrc), extra)
    }

    def apply(sequence: Short, timestamp: Int, ssrc: Int): RTPHeader =
      RTPHeader(0x80.toByte, 0x78, sequence, timestamp, ssrc)
  }
}
