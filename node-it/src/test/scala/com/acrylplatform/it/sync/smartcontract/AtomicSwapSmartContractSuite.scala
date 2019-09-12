package com.acrylplatform.it.sync.smartcontract

import com.typesafe.config.Config
import com.acrylplatform.account.AddressOrAlias
import com.acrylplatform.common.state.ByteStr
import com.acrylplatform.common.utils.EitherExt2
import com.acrylplatform.crypto
import com.acrylplatform.it.NodeConfigs
import com.acrylplatform.it.api.SyncHttpApi._
import com.acrylplatform.it.sync._
import com.acrylplatform.it.transactions.BaseTransactionSuite
import com.acrylplatform.transaction.Asset.Acryl
import com.acrylplatform.transaction.Proofs
import com.acrylplatform.transaction.smart.SetScriptTransaction
import com.acrylplatform.transaction.smart.script.ScriptCompiler
import com.acrylplatform.transaction.transfer._
import org.scalatest.CancelAfterFailure

/*
Scenario:
1. Alice's and swapBC1's balances initialisation
2. Create and setup smart contract for swapBC1
3. Alice funds swapBC1t
4. Alice can't take money from swapBC1
5.1 Bob takes funds because he knows secret hash and 5.2 after rollback wait height and Alice takes funds back
 */

class AtomicSwapSmartContractSuite extends BaseTransactionSuite with CancelAfterFailure {

  /*
  One node because:
  1. There is an expected behavior of rollback and a possible issue with this. When the node are going rollback,
     there is a chance, it has a downloaded (but don't applied) extension. After the rollback, it applies an extension.
     This breaks the test.
  2. We have RollbackSuite
   */
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(0))
      .withDefault(1)
      .buildNonConflicting()

  private val BobBC1: String   = sender.createAddress()
  private val AliceBC1: String = sender.createAddress()
  private val swapBC1: String  = sender.createAddress()

  private val AlicesPK = pkByAddress(AliceBC1)

  private val secretText = "some secret message from Alice"
  private val shaSecret  = "BN6RTYGWcwektQfSFzH8raYo9awaLgQ7pLyWLQY4S4F5"

  test("step1 - Balances initialization") {
    val toAliceBC1TxId = sender.transfer(sender.address, AliceBC1, 10 * transferAmount, minFee).id
    nodes.waitForHeightAriseAndTxPresent(toAliceBC1TxId)

    val toSwapBC1TxId = sender.transfer(sender.address, swapBC1, setScriptFee, minFee).id
    nodes.waitForHeightAriseAndTxPresent(toSwapBC1TxId)
  }

  test("step2 - Create and setup smart contract for swapBC1") {
    val beforeHeight = sender.height
    val scriptText   = s"""
    let Bob = Address(base58'$BobBC1')
    let Alice = Address(base58'$AliceBC1')

    match tx {
      case ttx: TransferTransaction =>
        let txToBob = (ttx.recipient == Bob) && (sha256(ttx.proofs[0]) == base58'$shaSecret') && ((20 + $beforeHeight) >= height)
        let backToAliceAfterHeight = ((height >= (21 + $beforeHeight)) && (ttx.recipient == Alice))

        txToBob || backToAliceAfterHeight
      case other => false
    }""".stripMargin

    val pkSwapBC1 = pkByAddress(swapBC1)
    val script    = ScriptCompiler(scriptText, isAssetScript = false).explicitGet()._1
    val sc1SetTx = SetScriptTransaction
      .selfSigned(sender = pkSwapBC1, script = Some(script), fee = setScriptFee, timestamp = System.currentTimeMillis())
      .explicitGet()

    val setScriptId = sender
      .signedBroadcast(sc1SetTx.json())
      .id

    nodes.waitForHeightAriseAndTxPresent(setScriptId)

    val swapBC1ScriptInfo = sender.addressScriptInfo(swapBC1)

    swapBC1ScriptInfo.script.isEmpty shouldBe false
    swapBC1ScriptInfo.scriptText.isEmpty shouldBe false
  }

  test("step3 - Alice makes transfer to swapBC1") {
    val txToSwapBC1 =
      TransferTransactionV2
        .selfSigned(
          assetId = Acryl,
          sender = pkByAddress(AliceBC1),
          recipient = AddressOrAlias.fromString(swapBC1).explicitGet(),
          amount = transferAmount + setScriptFee + smartFee,
          timestamp = System.currentTimeMillis(),
          feeAssetId = Acryl,
          feeAmount = setScriptFee + smartFee,
          attachment = Array.emptyByteArray
        )
        .explicitGet()

    val transferId = sender
      .signedBroadcast(txToSwapBC1.json())
      .id
    nodes.waitForHeightAriseAndTxPresent(transferId)
  }

  test("step4 - Alice cannot make transfer from swapBC1 if height is incorrect") {
    val txToSwapBC1 =
      TransferTransactionV2
        .selfSigned(
          assetId = Acryl,
          sender = pkByAddress(swapBC1),
          recipient = AddressOrAlias.fromString(AliceBC1).explicitGet(),
          amount = transferAmount,
          timestamp = System.currentTimeMillis(),
          feeAssetId = Acryl,
          feeAmount = setScriptFee + smartFee,
          attachment = Array.emptyByteArray
        )
        .explicitGet()

    assertBadRequest(sender.signedBroadcast(txToSwapBC1.json()))
  }

  test("step5 - Bob makes transfer; after revert Alice takes funds back") {
    val height = nodes.height.max

    val (bobBalance, bobEffBalance)     = miner.accountBalances(BobBC1)
    val (aliceBalance, aliceEffBalance) = miner.accountBalances(AliceBC1)
    val (swapBalance, swapEffBalance)   = miner.accountBalances(swapBC1)

    val unsigned =
      TransferTransactionV2
        .create(
          assetId = Acryl,
          sender = pkByAddress(swapBC1),
          recipient = AddressOrAlias.fromString(BobBC1).explicitGet(),
          amount = transferAmount,
          timestamp = System.currentTimeMillis(),
          feeAssetId = Acryl,
          feeAmount = setScriptFee + smartFee,
          attachment = Array.emptyByteArray,
          proofs = Proofs.empty
        )
        .explicitGet()

    val proof    = ByteStr(secretText.getBytes("UTF-8"))
    val sigAlice = ByteStr(crypto.sign(AlicesPK, unsigned.bodyBytes()))
    val signed   = unsigned.copy(proofs = Proofs(Seq(proof, sigAlice)))

    nodes.waitForHeightArise()
    val versionedTransferId = sender.signedBroadcast(signed.json()).id
    nodes.waitForHeightAriseAndTxPresent(versionedTransferId)

    miner.assertBalances(swapBC1,
                         swapBalance - transferAmount - (setScriptFee + smartFee),
                         swapEffBalance - transferAmount - (setScriptFee + smartFee))
    miner.assertBalances(BobBC1, bobBalance + transferAmount, bobEffBalance + transferAmount)
    miner.assertBalances(AliceBC1, aliceBalance, aliceEffBalance)

    nodes.rollback(height, false)

    nodes.waitForHeight(height + 20)

    miner.accountBalances(swapBC1)
    assertBadRequestAndMessage(miner.transactionInfo(versionedTransferId), "transactions does not exist", 404)

    val selfSignedToAlice = TransferTransactionV2
      .selfSigned(
        assetId = Acryl,
        sender = pkByAddress(swapBC1),
        recipient = AddressOrAlias.fromString(AliceBC1).explicitGet(),
        amount = transferAmount,
        timestamp = System.currentTimeMillis(),
        feeAssetId = Acryl,
        feeAmount = setScriptFee + smartFee,
        attachment = Array.emptyByteArray
      )
      .explicitGet()

    val transferToAlice =
      sender.signedBroadcast(selfSignedToAlice.json()).id
    nodes.waitForHeightAriseAndTxPresent(transferToAlice)

    miner.assertBalances(swapBC1,
                         swapBalance - transferAmount - (setScriptFee + smartFee),
                         swapEffBalance - transferAmount - (setScriptFee + smartFee))
    miner.assertBalances(BobBC1, bobBalance, bobEffBalance)
    miner.assertBalances(AliceBC1, aliceBalance + transferAmount, aliceEffBalance + transferAmount)
  }

}
