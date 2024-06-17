package co.rsk.federate.signing.hsm.message;

import static co.rsk.federate.EventsTestUtils.creatRejectedPeginLog;
import static co.rsk.federate.EventsTestUtils.createBatchPegoutCreatedLog;
import static co.rsk.federate.EventsTestUtils.createPegoutTransactionCreatedLog;
import static co.rsk.federate.EventsTestUtils.createReleaseRequestedLog;
import static co.rsk.federate.EventsTestUtils.createUpdateCollectionsLog;
import static co.rsk.federate.bitcoin.BitcoinTestUtils.coinListOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.TransactionInput;
import co.rsk.bitcoinj.core.TransactionWitness;
import co.rsk.bitcoinj.script.Script;
import co.rsk.core.RskAddress;
import co.rsk.core.bc.BlockHashesHelper;
import co.rsk.crypto.Keccak256;
import co.rsk.federate.bitcoin.BitcoinTestUtils;
import co.rsk.federate.signing.utils.TestUtils;
import co.rsk.peg.constants.BridgeConstants;
import co.rsk.peg.constants.BridgeMainNetConstants;
import co.rsk.peg.federation.Federation;
import co.rsk.peg.pegin.RejectedPeginReason;
import co.rsk.trie.Trie;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockHeaderBuilder;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.crypto.ECKey;
import org.ethereum.db.ReceiptStore;
import org.ethereum.db.TransactionInfo;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.PrecompiledContracts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.spongycastle.util.encoders.Hex;

class PowHSMSignerMessageBuilderTest {

    private static final int FIRST_OUTPUT_INDEX = 0;

    private static final BridgeConstants bridgeMainnetConstants = BridgeMainNetConstants.getInstance();
    private static final NetworkParameters btcMainnetParams = bridgeMainnetConstants.getBtcParams();
    private static final Address userAddress = BitcoinTestUtils.createP2PKHAddress(
        btcMainnetParams, "userAddress");
    private static final Federation activeFederation = TestUtils.createFederation(
        bridgeMainnetConstants.getBtcParams(), 9);

    private Transaction pegoutCreationRskTx;
    private Transaction pegoutConfirmationRskTx;
    private Block pegoutCreationBlock;
    private TransactionReceipt pegoutCreationRskTxReceipt;
    private TransactionInfo pegoutCreationRskTxInfo;

    private static List<Arguments> buildMessageForIndexArgProvider() {
        List<Arguments> arguments = new ArrayList<>();

        arguments.add(Arguments.of(Hex.decode("00"), Collections.singletonList(Coin.ZERO)));
        arguments.add(Arguments.of(Hex.decode("01"), Collections.singletonList(Coin.SATOSHI)));
        // 252 = FC, 187 = BB, 13_337 = FE9145DC00, 14_435_729 = FEDC4591
        arguments.add(Arguments.of(Hex.decode("FE80F0FA02FEC0687804FE00E1F505"),
            coinListOf(50_000_000, 75_000_000, 100_000_000)));

        return arguments;
    }

    @BeforeEach
    void setUp() {
        Keccak256 pegoutCreationRskTxHash = TestUtils.createHash(2);
        pegoutCreationRskTx = mock(Transaction.class);
        when(pegoutCreationRskTx.getHash()).thenReturn(pegoutCreationRskTxHash);
        when(pegoutCreationRskTx.getReceiveAddress()).thenReturn(PrecompiledContracts.BRIDGE_ADDR);

        Keccak256 pegoutConfirmationRskTxHash = TestUtils.createHash(3);
        pegoutConfirmationRskTx = mock(Transaction.class);
        when(pegoutCreationRskTx.getHash()).thenReturn(pegoutConfirmationRskTxHash);
        when(pegoutCreationRskTx.getReceiveAddress()).thenReturn(PrecompiledContracts.BRIDGE_ADDR);

        pegoutCreationBlock = createBlock(1, Collections.singletonList(pegoutCreationRskTx));

        pegoutCreationRskTxReceipt = new TransactionReceipt();
        pegoutCreationRskTxReceipt.setLogInfoList(Collections.emptyList());
        pegoutCreationRskTxReceipt.setTransaction(pegoutCreationRskTx);

        pegoutCreationRskTxInfo = mock(TransactionInfo.class);
        when(pegoutCreationRskTxInfo.getReceipt()).thenReturn(pegoutCreationRskTxReceipt);
        when(pegoutCreationRskTxInfo.getBlockHash()).thenReturn(
            pegoutCreationBlock.getHash().getBytes());
    }

    private Block createBlock(int blockNumber, List<Transaction> rskTxs) {
        int parentBlockNumber = blockNumber > 0 ? blockNumber - 1 : 0;
        BlockHeader blockHeader = new BlockHeaderBuilder(mock(ActivationConfig.class)).setNumber(
                blockNumber).setParentHashFromKeccak256(TestUtils.createHash(parentBlockNumber))
            .build();
        return new Block(blockHeader, rskTxs, Collections.emptyList(), true, true);
    }

    @Test
    void createHSMVersion2Message() throws SignerMessageBuilderException {
        //Arrange
        ReceiptStore receiptStore = mock(ReceiptStore.class);

        Transaction rskTx = mock(Transaction.class);
        Keccak256 rskTxHash = Keccak256.ZERO_HASH;
        when(rskTx.getHash()).thenReturn(rskTxHash);

        byte[] rskBlockHash = new byte[]{0x2};

        TransactionReceipt txReceipt = new TransactionReceipt();
        txReceipt.setTransaction(rskTx);
        TransactionInfo txInfo = new TransactionInfo(txReceipt, rskBlockHash, 0);

        BlockHeader blockHeader = mock(BlockHeader.class);
        when(blockHeader.getHash()).thenReturn(Keccak256.ZERO_HASH);
        byte[] trieRoot = BlockHashesHelper.getTxTrieRoot(Collections.singletonList(rskTx), true);
        when(blockHeader.getTxTrieRoot()).thenReturn(trieRoot);

        Block block = new Block(blockHeader, Collections.singletonList(rskTx),
            Collections.emptyList(), true, true);

        when(receiptStore.get(rskTxHash.getBytes(), Keccak256.ZERO_HASH.getBytes())).thenReturn(
            Optional.of(txInfo));

        BtcTransaction releaseTx = createPegout();
        int inputIndex = 0;

        //Act
        PowHSMSignerMessageBuilder sigMessVersion2 = new PowHSMSignerMessageBuilder(receiptStore,
            new ReleaseCreationInformation(block, txReceipt, rskTxHash, releaseTx, rskTxHash));
        PowHSMSignerMessage message = (PowHSMSignerMessage) sigMessVersion2.buildMessageForIndex(
            inputIndex);
        PowHSMSignerMessage message2 = (PowHSMSignerMessage) sigMessVersion2.buildMessageForIndex(
            inputIndex);

        //Assert
        List<Trie> receiptMerkleProof = BlockHashesHelper.calculateReceiptsTrieRootFor(block,
            receiptStore, rskTxHash);
        String[] encodedReceipts = new String[receiptMerkleProof.size()];
        for (int i = 0; i < encodedReceipts.length; i++) {
            encodedReceipts[i] = Hex.toHexString(receiptMerkleProof.get(i).toMessage());
        }
        Sha256Hash sigHash = releaseTx.hashForSignature(0, activeFederation.getRedeemScript(),
            BtcTransaction.SigHash.ALL, false);

        assertEquals(Hex.toHexString(releaseTx.bitcoinSerialize()),
            message.getBtcTransactionSerialized());
        assertEquals(inputIndex, message.getInputIndex());
        assertEquals(Hex.toHexString(txReceipt.getEncoded()), message.getTransactionReceipt());
        assertArrayEquals(encodedReceipts, message.getReceiptMerkleProof());
        assertEquals(sigHash, message.getSigHash());
        // Building message twice returns same message
        assertEquals(message, message2);
    }

    @Test
    void buildMessageForIndex_fails() {
        Transaction rskTx = mock(Transaction.class);
        when(rskTx.getHash()).thenReturn(Keccak256.ZERO_HASH);
        BlockHeaderBuilder blockHeaderBuilder = new BlockHeaderBuilder(
            mock(ActivationConfig.class));
        Block block = new Block(blockHeaderBuilder.setNumber(1).build(),
            Collections.singletonList(rskTx), Collections.emptyList(), true, true);
        TransactionReceipt transactionReceipt = mock(TransactionReceipt.class);
        when(transactionReceipt.getTransaction()).thenReturn(rskTx);
        ReceiptStore receiptStore = mock(ReceiptStore.class);
        when(receiptStore.get(any(byte[].class), any(byte[].class))).thenReturn(Optional.empty());

        ReleaseCreationInformation releaseCreationInformation = new ReleaseCreationInformation(
            block, transactionReceipt, rskTx.getHash(), mock(BtcTransaction.class),
            rskTx.getHash());
        PowHSMSignerMessageBuilder sigMessVersion2 = new PowHSMSignerMessageBuilder(receiptStore,
            releaseCreationInformation);
        assertThrows(SignerMessageBuilderException.class,
            () -> sigMessVersion2.buildMessageForIndex(0));
    }

    @Test
    void buildMessageForIndex_whenLegacyBatchPegoutHasTransactionCreatedEvent_ok()
        throws SignerMessageBuilderException {
        // arrange
        BtcTransaction pegoutBtcTx = createPegout();

        List<LogInfo> logs = new ArrayList<>();

        ECKey senderKey = new ECKey();
        RskAddress senderAddress = new RskAddress(senderKey.getAddress());
        LogInfo updateCollectionsLog = createUpdateCollectionsLog(senderAddress);
        logs.add(updateCollectionsLog);

        Coin pegoutAmount = bridgeMainnetConstants.getMinimumPegoutTxValue();
        LogInfo releaseRequestedLog = createReleaseRequestedLog(pegoutCreationRskTx.getHash(),
            pegoutBtcTx.getHash(), pegoutAmount);
        logs.add(releaseRequestedLog);

        List<Keccak256> pegoutRequestRskTxHashes = Collections.singletonList(
            TestUtils.createHash(10));
        LogInfo batchPegoutCreatedLog = createBatchPegoutCreatedLog(pegoutBtcTx.getHash(),
            pegoutRequestRskTxHashes);
        logs.add(batchPegoutCreatedLog);

        LogInfo pegoutTransactionCreatedLog = createPegoutTransactionCreatedLog(
            pegoutBtcTx.getHash(), new byte[]{});
        logs.add(pegoutTransactionCreatedLog);

        pegoutCreationRskTxReceipt.setLogInfoList(logs);

        ReceiptStore receiptStore = mock(ReceiptStore.class);
        when(receiptStore.get(pegoutCreationRskTx.getHash().getBytes(),
            pegoutCreationBlock.getHash().getBytes())).thenReturn(
            Optional.of(pegoutCreationRskTxInfo));

        ReleaseCreationInformation releaseCreationInformation = new ReleaseCreationInformation(
            pegoutCreationBlock, pegoutCreationRskTxReceipt, pegoutCreationRskTx.getHash(),
            pegoutBtcTx,
            pegoutConfirmationRskTx.getHash());
        PowHSMSignerMessageBuilder powHSMSignerMessageBuilder = new PowHSMSignerMessageBuilder(
            receiptStore, releaseCreationInformation);

        // act
        List<Coin> expectedOutpointValues = Collections.emptyList();
        List<Coin> actualOutpointValues = releaseCreationInformation.getUtxoOutpointValues();
        assertArrayEquals(actualOutpointValues.toArray(), expectedOutpointValues.toArray());

        int inputIndex = 0;

        SignerMessage actualSignerMessage = powHSMSignerMessageBuilder.buildMessageForIndex(
            inputIndex);

        // assertions
        assertEquals(actualSignerMessage.getClass(), PowHSMSignerMessage.class);

        PowHSMSignerMessage actualPowHSMSignerMessage = (PowHSMSignerMessage) actualSignerMessage;

        int actualInputIndex = actualPowHSMSignerMessage.getInputIndex();
        assertEquals(inputIndex, actualInputIndex);

        Sha256Hash expectedSigHash = pegoutBtcTx.hashForSignature(inputIndex,
            activeFederation.getRedeemScript(),
            BtcTransaction.SigHash.ALL, false);
        assertEquals(expectedSigHash, actualPowHSMSignerMessage.getSigHash());

        String expectedBtcTxSerialized = Hex.toHexString(pegoutBtcTx.bitcoinSerialize());
        String ActualBtcTxSerialized = actualPowHSMSignerMessage.getBtcTransactionSerialized();
        assertEquals(expectedBtcTxSerialized, ActualBtcTxSerialized);

        List<Trie> receiptMerkleProof = BlockHashesHelper.calculateReceiptsTrieRootFor(
            pegoutCreationBlock,
            receiptStore, pegoutCreationRskTx.getHash());
        String[] expectedReceiptMerkleProof = new String[receiptMerkleProof.size()];
        for (int i = 0; i < expectedReceiptMerkleProof.length; i++) {
            expectedReceiptMerkleProof[i] = Hex.toHexString(
                receiptMerkleProof.get(i).toMessage());
        }
        assertArrayEquals(expectedReceiptMerkleProof,
            actualPowHSMSignerMessage.getReceiptMerkleProof());
    }

    @ParameterizedTest
    @MethodSource("buildMessageForIndexArgProvider")
    void buildMessageForIndex_whenBatchPegoutHasTransactionCreatedEvent_ok(
        byte[] serializedOutpointValues, List<Coin> expectedOutpointValues)
        throws SignerMessageBuilderException {
        // arrange
        BtcTransaction pegoutBtcTx = createSegwitPegout(expectedOutpointValues);

        List<LogInfo> logs = new ArrayList<>();

        ECKey senderKey = new ECKey();
        RskAddress senderAddress = new RskAddress(senderKey.getAddress());
        LogInfo updateCollectionsLog = createUpdateCollectionsLog(senderAddress);
        logs.add(updateCollectionsLog);

        Coin pegoutAmount = bridgeMainnetConstants.getMinimumPegoutTxValue();
        LogInfo releaseRequestedLog = createReleaseRequestedLog(pegoutCreationRskTx.getHash(),
            pegoutBtcTx.getHash(), pegoutAmount);
        logs.add(releaseRequestedLog);

        List<Keccak256> pegoutRequestRskTxHashes = Collections.singletonList(
            TestUtils.createHash(10));
        LogInfo batchPegoutCreatedLog = createBatchPegoutCreatedLog(pegoutBtcTx.getHash(),
            pegoutRequestRskTxHashes);
        logs.add(batchPegoutCreatedLog);

        LogInfo pegoutTransactionCreatedLog = createPegoutTransactionCreatedLog(
            pegoutBtcTx.getHash(), serializedOutpointValues);
        logs.add(pegoutTransactionCreatedLog);

        pegoutCreationRskTxReceipt.setLogInfoList(logs);

        ReceiptStore receiptStore = mock(ReceiptStore.class);
        when(receiptStore.get(pegoutCreationRskTx.getHash().getBytes(),
            pegoutCreationBlock.getHash().getBytes())).thenReturn(
            Optional.of(pegoutCreationRskTxInfo));

        ReleaseCreationInformation releaseCreationInformation = new ReleaseCreationInformation(
            pegoutCreationBlock, pegoutCreationRskTxReceipt, pegoutCreationRskTx.getHash(),
            pegoutBtcTx,
            pegoutConfirmationRskTx.getHash());
        PowHSMSignerMessageBuilder powHSMSignerMessageBuilder = new PowHSMSignerMessageBuilder(
            receiptStore, releaseCreationInformation);

        // act
        List<Coin> actualOutpointValues = releaseCreationInformation.getUtxoOutpointValues();
        assertArrayEquals(actualOutpointValues.toArray(), expectedOutpointValues.toArray());

        int numOfInputsToSign = expectedOutpointValues.size();

        for (int inputIndex = 0; inputIndex < numOfInputsToSign; inputIndex++) {
            SignerMessage actualSignerMessage = powHSMSignerMessageBuilder.buildMessageForIndex(
                inputIndex);

            // assertions
            assertEquals(actualSignerMessage.getClass(), PowHSMSignerMessage.class);

            PowHSMSignerMessage actualPowHSMSignerMessage = (PowHSMSignerMessage) actualSignerMessage;

            int actualInputIndex = actualPowHSMSignerMessage.getInputIndex();
            assertEquals(inputIndex, actualInputIndex);

            Sha256Hash expectedSigHash = pegoutBtcTx.hashForSignature(inputIndex,
                activeFederation.getRedeemScript(),
                BtcTransaction.SigHash.ALL, false);
            assertEquals(expectedSigHash, actualPowHSMSignerMessage.getSigHash());

            String expectedBtcTxSerialized = Hex.toHexString(pegoutBtcTx.bitcoinSerialize());
            String ActualBtcTxSerialized = actualPowHSMSignerMessage.getBtcTransactionSerialized();
            assertEquals(expectedBtcTxSerialized, ActualBtcTxSerialized);

            List<Trie> receiptMerkleProof = BlockHashesHelper.calculateReceiptsTrieRootFor(
                pegoutCreationBlock,
                receiptStore, pegoutCreationRskTx.getHash());
            String[] expectedReceiptMerkleProof = new String[receiptMerkleProof.size()];
            for (int i = 0; i < expectedReceiptMerkleProof.length; i++) {
                expectedReceiptMerkleProof[i] = Hex.toHexString(
                    receiptMerkleProof.get(i).toMessage());
            }
            assertArrayEquals(expectedReceiptMerkleProof,
                actualPowHSMSignerMessage.getReceiptMerkleProof());
        }
    }

    @ParameterizedTest
    @MethodSource("buildMessageForIndexArgProvider")
    void buildMessageForIndex_whenMigrationPegoutHasTransactionCreatedEvent_ok(
        byte[] serializedOutpointValues, List<Coin> expectedOutpointValues)
        throws SignerMessageBuilderException {
        // arrange
        BtcTransaction pegoutBtcTx = createSegwitPegout(expectedOutpointValues);

        List<LogInfo> logs = new ArrayList<>();

        ECKey senderKey = new ECKey();
        RskAddress senderAddress = new RskAddress(senderKey.getAddress());
        LogInfo updateCollectionsLog = createUpdateCollectionsLog(senderAddress);
        logs.add(updateCollectionsLog);

        Coin pegoutAmount = bridgeMainnetConstants.getMinimumPegoutTxValue();
        LogInfo releaseRequestedLog = createReleaseRequestedLog(pegoutCreationRskTx.getHash(),
            pegoutBtcTx.getHash(), pegoutAmount);
        logs.add(releaseRequestedLog);

        LogInfo pegoutTransactionCreatedLog = createPegoutTransactionCreatedLog(
            pegoutBtcTx.getHash(), serializedOutpointValues);
        logs.add(pegoutTransactionCreatedLog);

        pegoutCreationRskTxReceipt.setLogInfoList(logs);

        ReceiptStore receiptStore = mock(ReceiptStore.class);
        when(receiptStore.get(pegoutCreationRskTx.getHash().getBytes(),
            pegoutCreationBlock.getHash().getBytes())).thenReturn(
            Optional.of(pegoutCreationRskTxInfo));

        ReleaseCreationInformation releaseCreationInformation = new ReleaseCreationInformation(
            pegoutCreationBlock, pegoutCreationRskTxReceipt, pegoutCreationRskTx.getHash(),
            pegoutBtcTx,
            pegoutConfirmationRskTx.getHash());
        PowHSMSignerMessageBuilder powHSMSignerMessageBuilder = new PowHSMSignerMessageBuilder(
            receiptStore, releaseCreationInformation);

        // act
        List<Coin> actualOutpointValues = releaseCreationInformation.getUtxoOutpointValues();
        assertArrayEquals(actualOutpointValues.toArray(), expectedOutpointValues.toArray());

        int numOfInputsToSign = expectedOutpointValues.size();

        for (int inputIndex = 0; inputIndex < numOfInputsToSign; inputIndex++) {
            SignerMessage actualSignerMessage = powHSMSignerMessageBuilder.buildMessageForIndex(
                inputIndex);

            // assertions
            assertEquals(actualSignerMessage.getClass(), PowHSMSignerMessage.class);

            PowHSMSignerMessage actualPowHSMSignerMessage = (PowHSMSignerMessage) actualSignerMessage;

            int actualInputIndex = actualPowHSMSignerMessage.getInputIndex();
            assertEquals(inputIndex, actualInputIndex);

            Sha256Hash expectedSigHash = pegoutBtcTx.hashForSignature(inputIndex,
                activeFederation.getRedeemScript(),
                BtcTransaction.SigHash.ALL, false);
            assertEquals(expectedSigHash, actualPowHSMSignerMessage.getSigHash());

            String expectedBtcTxSerialized = Hex.toHexString(pegoutBtcTx.bitcoinSerialize());
            String ActualBtcTxSerialized = actualPowHSMSignerMessage.getBtcTransactionSerialized();
            assertEquals(expectedBtcTxSerialized, ActualBtcTxSerialized);

            List<Trie> receiptMerkleProof = BlockHashesHelper.calculateReceiptsTrieRootFor(
                pegoutCreationBlock,
                receiptStore, pegoutCreationRskTx.getHash());
            String[] expectedReceiptMerkleProof = new String[receiptMerkleProof.size()];
            for (int i = 0; i < expectedReceiptMerkleProof.length; i++) {
                expectedReceiptMerkleProof[i] = Hex.toHexString(
                    receiptMerkleProof.get(i).toMessage());
            }
            assertArrayEquals(expectedReceiptMerkleProof,
                actualPowHSMSignerMessage.getReceiptMerkleProof());
        }
    }

    @ParameterizedTest
    @MethodSource("buildMessageForIndexArgProvider")
    void buildMessageForIndex_whenRejectedPeginHasTransactionCreatedEvent_ok(
        byte[] serializedOutpointValues, List<Coin> expectedOutpointValues)
        throws SignerMessageBuilderException {
        // arrange
        BtcTransaction pegoutBtcTx = createSegwitPegout(expectedOutpointValues);

        List<LogInfo> logs = new ArrayList<>();

        LogInfo rejectedPeginLog = creatRejectedPeginLog(pegoutBtcTx.getHash(),
            RejectedPeginReason.LEGACY_PEGIN_MULTISIG_SENDER);
        logs.add(rejectedPeginLog);

        Coin pegoutAmount = bridgeMainnetConstants.getMinimumPegoutTxValue();
        LogInfo releaseRequestedLog = createReleaseRequestedLog(pegoutCreationRskTx.getHash(),
            pegoutBtcTx.getHash(), pegoutAmount);
        logs.add(releaseRequestedLog);

        LogInfo pegoutTransactionCreatedLog = createPegoutTransactionCreatedLog(
            pegoutBtcTx.getHash(), serializedOutpointValues);
        logs.add(pegoutTransactionCreatedLog);

        pegoutCreationRskTxReceipt.setLogInfoList(logs);

        ReceiptStore receiptStore = mock(ReceiptStore.class);
        when(receiptStore.get(pegoutCreationRskTx.getHash().getBytes(),
            pegoutCreationBlock.getHash().getBytes())).thenReturn(
            Optional.of(pegoutCreationRskTxInfo));

        ReleaseCreationInformation releaseCreationInformation = new ReleaseCreationInformation(
            pegoutCreationBlock, pegoutCreationRskTxReceipt, pegoutCreationRskTx.getHash(),
            pegoutBtcTx,
            pegoutConfirmationRskTx.getHash());
        PowHSMSignerMessageBuilder powHSMSignerMessageBuilder = new PowHSMSignerMessageBuilder(
            receiptStore, releaseCreationInformation);

        // act
        List<Coin> actualOutpointValues = releaseCreationInformation.getUtxoOutpointValues();
        assertArrayEquals(actualOutpointValues.toArray(), expectedOutpointValues.toArray());

        int numOfInputsToSign = expectedOutpointValues.size();

        for (int inputIndex = 0; inputIndex < numOfInputsToSign; inputIndex++) {
            SignerMessage actualSignerMessage = powHSMSignerMessageBuilder.buildMessageForIndex(
                inputIndex);

            // assertions
            assertEquals(actualSignerMessage.getClass(), PowHSMSignerMessage.class);

            PowHSMSignerMessage actualPowHSMSignerMessage = (PowHSMSignerMessage) actualSignerMessage;

            int actualInputIndex = actualPowHSMSignerMessage.getInputIndex();
            assertEquals(inputIndex, actualInputIndex);

            Sha256Hash expectedSigHash = pegoutBtcTx.hashForSignature(inputIndex,
                activeFederation.getRedeemScript(),
                BtcTransaction.SigHash.ALL, false);
            assertEquals(expectedSigHash, actualPowHSMSignerMessage.getSigHash());

            String expectedBtcTxSerialized = Hex.toHexString(pegoutBtcTx.bitcoinSerialize());
            String ActualBtcTxSerialized = actualPowHSMSignerMessage.getBtcTransactionSerialized();
            assertEquals(expectedBtcTxSerialized, ActualBtcTxSerialized);

            List<Trie> receiptMerkleProof = BlockHashesHelper.calculateReceiptsTrieRootFor(
                pegoutCreationBlock,
                receiptStore, pegoutCreationRskTx.getHash());
            String[] expectedReceiptMerkleProof = new String[receiptMerkleProof.size()];
            for (int i = 0; i < expectedReceiptMerkleProof.length; i++) {
                expectedReceiptMerkleProof[i] = Hex.toHexString(
                    receiptMerkleProof.get(i).toMessage());
            }
            assertArrayEquals(expectedReceiptMerkleProof,
                actualPowHSMSignerMessage.getReceiptMerkleProof());
        }
    }

    private Script createBaseInputScriptThatSpendsFromTheFederation(Federation federation) {
        Script scriptPubKey = federation.getP2SHScript();
        return scriptPubKey.createEmptyInputScript(null, federation.getRedeemScript());
    }

    private BtcTransaction createPegout() {
        Coin fundingAmount = Coin.COIN;
        Coin amountToSend = bridgeMainnetConstants.getMinimumPegoutTxValue();
        BtcTransaction fundingTransaction = new BtcTransaction(btcMainnetParams);
        fundingTransaction.addInput(
            BitcoinTestUtils.createHash(1),
            FIRST_OUTPUT_INDEX,
            new Script(new byte[]{})
        );
        fundingTransaction.addOutput(fundingAmount, activeFederation.getAddress());

        BtcTransaction pegoutBtcTx = new BtcTransaction(btcMainnetParams);
        TransactionInput addedInput = pegoutBtcTx.addInput(
            fundingTransaction.getOutput(FIRST_OUTPUT_INDEX));
        Script inputScriptThatSpendsFromTheFederation = createBaseInputScriptThatSpendsFromTheFederation(
            activeFederation);
        addedInput.setScriptSig(inputScriptThatSpendsFromTheFederation);
        pegoutBtcTx.addOutput(amountToSend, userAddress);

        return pegoutBtcTx;
    }

    private BtcTransaction createSegwitPegout(List<Coin> outpointValues) {
        BtcTransaction fundingTransaction = new BtcTransaction(btcMainnetParams);
        fundingTransaction.addInput(
            BitcoinTestUtils.createHash(1),
            FIRST_OUTPUT_INDEX,
            new Script(new byte[]{})
        );

        for (Coin outpointValue : outpointValues) {
            fundingTransaction.addOutput(outpointValue, activeFederation.getAddress());
        }

        BtcTransaction segwitPegoutBtcTx = new BtcTransaction(btcMainnetParams);

        Script inputScriptThatSpendsFromTheFederation = createBaseInputScriptThatSpendsFromTheFederation(
            activeFederation);
        TransactionWitness txWitness = new TransactionWitness(outpointValues.size());
        for (int i = 0; i < outpointValues.size(); i++) {
            TransactionInput addedInput = segwitPegoutBtcTx.addInput(
                fundingTransaction.getOutput(i));
            addedInput.setScriptSig(inputScriptThatSpendsFromTheFederation);
            txWitness.setPush(i, new byte[]{0x1});
            segwitPegoutBtcTx.setWitness(i, txWitness);
        }
        return segwitPegoutBtcTx;
    }
}
