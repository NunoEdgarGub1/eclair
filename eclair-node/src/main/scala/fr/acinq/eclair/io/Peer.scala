package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, LoggingFSM, PoisonPill, Props, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.{HandshakeCompleted, Listener}
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.router.SendRoutingState
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Features, NodeParams}

import scala.util.Random

// @formatter:off

case object Reconnect
//case class ChannelIdSwitch(previousId: Long, nextId: Long)

sealed trait OfflineChannel
case class BrandNewChannel(c: NewChannel) extends OfflineChannel
case class HotChannel(channelId: BinaryData, a: ActorRef) extends OfflineChannel

sealed trait Data
case class DisconnectedData(offlineChannels: Seq[OfflineChannel]) extends Data
case class InitializingData(transport: ActorRef, offlineChannels: Seq[OfflineChannel]) extends Data
case class ConnectedData(transport: ActorRef, remoteInit: Init, channels: Map[BinaryData, ActorRef]) extends Data

sealed trait State
case object DISCONNECTED extends State
case object INITIALIZING extends State
case object CONNECTED extends State

case class PeerRecord(id: PublicKey, address: InetSocketAddress)

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class Peer(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) extends LoggingFSM[State, Data] {

  import Peer._

  startWith(DISCONNECTED, DisconnectedData(Nil))

  when(DISCONNECTED) {
    case Event(state: HasCommitments, d@DisconnectedData(offlineChannels)) =>
      val channel = spawnChannel(nodeParams, context.system.deadLetters)
      channel ! INPUT_RESTORED(state)
      stay using d.copy(offlineChannels = offlineChannels :+ HotChannel(state.channelId, channel))

    case Event(c: NewChannel, d@DisconnectedData(offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(Reconnect, _) if address_opt.isDefined =>
      context.parent forward NewConnection(remoteNodeId, address_opt.get, None)
      stay

    case Event(HandshakeCompleted(transport, _), DisconnectedData(channels)) =>
      log.info(s"registering as a listener to $transport")
      transport ! Listener(self)
      context watch transport
      transport ! Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)
      goto(INITIALIZING) using InitializingData(transport, channels)
  }

  when(INITIALIZING) {
    case Event(c: NewChannel, d@InitializingData(_, offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(remoteInit: Init, InitializingData(transport, offlineChannels)) =>
      // we store the ip upon successful connection
      address_opt.foreach(address => nodeParams.peersDb.put(remoteNodeId, PeerRecord(remoteNodeId, address)))
      import fr.acinq.eclair.Features._
      log.info(s"$remoteNodeId has features: channelPublic=${channelPublic(remoteInit.localFeatures)} initialRoutingSync=${initialRoutingSync(remoteInit.localFeatures)}")
      if (Features.areFeaturesCompatible(nodeParams.localFeatures, remoteInit.localFeatures)) {
        if (Features.initialRoutingSync(remoteInit.localFeatures) != Unset) {
          router ! SendRoutingState(transport)
        }
        // let's bring existing/requested channels online
        val channels = offlineChannels.map {
          case BrandNewChannel(c) =>
            self ! c
            None
          case HotChannel(channelId, channel) =>
            channel ! INPUT_RECONNECTED(transport)
            Some((channelId -> channel))
        }.flatten.toMap
        goto(CONNECTED) using ConnectedData(transport, remoteInit, channels)
      } else {
        log.warning(s"incompatible features, disconnecting")
        transport ! PoisonPill
        stay
      }

    case Event(Terminated(actor), InitializingData(transport, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(channels)
  }

  when(CONNECTED) {

    case Event(err@Error(channelId, reason), ConnectedData(transport, _, channels)) if channelId == CHANNELID_ZERO =>
      log.error(s"connection-level error, failing all channels! reason=${new String(reason)}")
      channels.values.foreach(_ forward err)
      transport ! PoisonPill
      stay

    case Event(msg: HasTemporaryChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.temporaryChannelId) =>
      val channel = channels(msg.temporaryChannelId)
      channel forward msg
      stay

    case Event(msg: HasChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.channelId) =>
      val channel = channels(msg.channelId)
      channel forward msg
      stay

    case Event(ChannelIdAssigned(channel, temporaryChannelId, channelId), d@ConnectedData(_, _, channels)) if channels.contains(temporaryChannelId) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      stay using d.copy(channels = channels - temporaryChannelId + (channelId -> channel))

    case Event(c: NewChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis} and pushMsat=${c.pushMsat}")
      val (channel, localParams) = createChannel(nodeParams, transport, funder = true, c.fundingSatoshis.toLong)
      val temporaryChannelId = randomTemporaryChannelId
      channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, localParams, transport, remoteInit)
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg: OpenChannel, d@ConnectedData(transport, remoteInit, channels)) if !channels.contains(msg.temporaryChannelId) =>
      log.info(s"accepting a new channel to $remoteNodeId")
      val (channel, localParams) = createChannel(nodeParams, transport, funder = false, fundingSatoshis = msg.fundingSatoshis)
      val temporaryChannelId = msg.temporaryChannelId
      channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, transport, remoteInit)
      channel ! msg
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg: RoutingMessage, ConnectedData(transport, _, _)) if sender == router =>
      transport forward msg
      stay

    case Event(msg: RoutingMessage, _) =>
      router forward msg
      stay

    case Event(Terminated(actor), ConnectedData(transport, _, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      goto(DISCONNECTED) using DisconnectedData(channels.toSeq.map(c => HotChannel(c._1, c._2)))

    case Event(Terminated(actor), d@ConnectedData(transport, _, channels)) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      log.info(s"channel closed: channelId=$channelId")
      if (channels.size == 1) {
        log.info(s"that was the last open channel, closing the connection")
        transport ! PoisonPill
        // NB: we could terminate the peer, but it would create a race issue with concurrent NewChannel requests that would go to DeadLetters without switchboard being aware
        // for now we just leave it as-is
      }
      stay using d.copy(channels = channels - channelId)

  }

  def createChannel(nodeParams: NodeParams, transport: ActorRef, funder: Boolean, fundingSatoshis: Long): (ActorRef, LocalParams) = {
    val localParams = makeChannelParams(nodeParams, defaultFinalScriptPubKey, funder, fundingSatoshis)
    val channel = spawnChannel(nodeParams, transport)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, transport: ActorRef): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, remoteNodeId, watcher, router, relayer), s"channel-${context.children.size}")
    context watch channel
    channel
  }

}

object Peer {

  val CHANNELID_ZERO = BinaryData("00" * 32)

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) = Props(new Peer(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, defaultFinalScriptPubKey))

  def generateKey(nodeParams: NodeParams, keyPath: Seq[Long]): PrivateKey = DeterministicWallet.derivePrivateKey(nodeParams.extendedPrivateKey, keyPath).privateKey

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: BinaryData, isFunder: Boolean, fundingSatoshis: Long): LocalParams = {
    // all secrets are generated from the main seed
    val keyIndex = Random.nextInt(1000).toLong
    LocalParams(
      nodeId = nodeParams.privateKey.publicKey,
      dustLimitSatoshis = nodeParams.dustLimitSatoshis,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserveSatoshis = (nodeParams.reserveToFundingRatio * fundingSatoshis).toLong,
      htlcMinimumMsat = nodeParams.htlcMinimumMsat,
      feeratePerKw = nodeParams.feeratePerKw,
      toSelfDelay = nodeParams.delayBlocks,
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      fundingPrivKey = generateKey(nodeParams, keyIndex :: 0L :: Nil),
      revocationSecret = generateKey(nodeParams, keyIndex :: 1L :: Nil),
      paymentKey = generateKey(nodeParams, keyIndex :: 2L :: Nil),
      delayedPaymentKey = generateKey(nodeParams, keyIndex :: 3L :: Nil),
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      shaSeed = Crypto.sha256(generateKey(nodeParams, keyIndex :: 4L :: Nil).toBin), // TODO: check that
      isFunder = isFunder,
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures)
  }

  def randomTemporaryChannelId: BinaryData = {
    val bin = Array.fill[Byte](32)(0)
    Random.nextBytes(bin)
    bin
  }
}
