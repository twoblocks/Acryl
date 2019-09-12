package com.acrylplatform.network

import com.acrylplatform.block.Block
import com.acrylplatform.transaction.Transaction
import com.acrylplatform.utils.ScorexLogging
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import monix.reactive.subjects.ConcurrentSubject

@Sharable
class MessageObserver extends ChannelInboundHandlerAdapter with ScorexLogging {

  implicit val scheduler = monix.execution.Scheduler.fixedPool("message-observer", 2)

  private val signatures          = ConcurrentSubject.publish[(Channel, Signatures)]
  private val blocks              = ConcurrentSubject.publish[(Channel, Block)]
  private val blockchainScores    = ConcurrentSubject.publish[(Channel, BigInt)]
  private val microblockInvs      = ConcurrentSubject.publish[(Channel, MicroBlockInv)]
  private val microblockResponses = ConcurrentSubject.publish[(Channel, MicroBlockResponse)]
  private val transactions        = ConcurrentSubject.publish[(Channel, Transaction)]

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case b: Block               => blocks.onNext((ctx.channel(), b))
    case sc: BigInt             => blockchainScores.onNext((ctx.channel(), sc))
    case s: Signatures          => signatures.onNext((ctx.channel(), s))
    case mbInv: MicroBlockInv   => microblockInvs.onNext((ctx.channel(), mbInv))
    case mb: MicroBlockResponse => microblockResponses.onNext((ctx.channel(), mb))
    case tx: Transaction        => transactions.onNext((ctx.channel(), tx))
    case _                      => super.channelRead(ctx, msg)

  }

  def shutdown(): Unit = {
    signatures.onComplete()
    blocks.onComplete()
    blockchainScores.onComplete()
    microblockInvs.onComplete()
    microblockResponses.onComplete()
    transactions.onComplete()
  }
}

object MessageObserver {
  type Messages = (ChannelObservable[Signatures],
                   ChannelObservable[Block],
                   ChannelObservable[BigInt],
                   ChannelObservable[MicroBlockInv],
                   ChannelObservable[MicroBlockResponse],
                   ChannelObservable[Transaction])

  def apply(): (MessageObserver, Messages) = {
    val mo = new MessageObserver()
    (mo, (mo.signatures, mo.blocks, mo.blockchainScores, mo.microblockInvs, mo.microblockResponses, mo.transactions))
  }
}
