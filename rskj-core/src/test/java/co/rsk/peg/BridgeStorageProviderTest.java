/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import static co.rsk.peg.BridgeStorageIndexKey.*;
import static co.rsk.peg.federation.FederationFormatVersion.*;
import static co.rsk.peg.storage.FederationStorageIndexKey.*;
import static org.ethereum.TestUtils.mockAddress;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.rsk.bitcoinj.core.*;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.config.TestSystemProperties;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.db.MutableTrieCache;
import co.rsk.db.MutableTrieImpl;
import co.rsk.peg.bitcoin.CoinbaseInformation;
import co.rsk.peg.bitcoin.SimpleBtcTransaction;
import co.rsk.peg.constants.*;
import co.rsk.peg.federation.*;
import co.rsk.peg.federation.constants.FederationConstants;
import co.rsk.peg.flyover.FlyoverFederationInformation;
import co.rsk.peg.storage.BridgeStorageAccessorImpl;
import co.rsk.peg.storage.StorageAccessor;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import co.rsk.trie.*;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.TestUtils;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.Repository;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.MutableRepository;
import org.ethereum.util.RLP;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.PrecompiledContracts;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Created by ajlopez on 6/7/2016.
 */
@ExtendWith(MockitoExtension.class)
// to avoid Junit5 unnecessary stub error due to some setup generalizations
@MockitoSettings(strictness = Strictness.LENIENT)
class BridgeStorageProviderTest {
    private static final byte FAST_BRIDGE_FEDERATION_SCRIPT_HASH_TRUE_VALUE_TEST = (byte) 1;
    private static final int STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION = STANDARD_MULTISIG_FEDERATION.getFormatVersion();
    private static final int NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION = NON_STANDARD_ERP_FEDERATION.getFormatVersion();
    private static final int P2SH_ERP_FEDERATION_FORMAT_VERSION = P2SH_ERP_FEDERATION.getFormatVersion();

    private final TestSystemProperties config = new TestSystemProperties();
    private final ActivationConfig.ForBlock activationsBeforeFork = ActivationConfigsForTest.genesis().forBlock(0L);
    private final ActivationConfig.ForBlock activationsAllForks = ActivationConfigsForTest.all().forBlock(0);
    private final BridgeTestNetConstants bridgeTestnetInstance = BridgeTestNetConstants.getInstance();
    private final FederationConstants federationTestnetConstants = bridgeTestnetInstance.getFederationConstants();
    private final NetworkParameters testnetBtcParams = bridgeTestnetInstance.getBtcParams();

    private final RskAddress bridgeAddress = PrecompiledContracts.BRIDGE_ADDR;

    private int transactionOffset;

    @Test
    void createInstance() throws IOException {
        Repository repository = createRepository();

        BridgeStorageProvider bridgeStorageProvider = createBridgeStorageProvider(repository, testnetBtcParams, activationsBeforeFork);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        ReleaseRequestQueue releaseRequestQueue = bridgeStorageProvider.getReleaseRequestQueue();

        Assertions.assertNotNull(releaseRequestQueue);
        Assertions.assertEquals(0, releaseRequestQueue.getEntries().size());

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = bridgeStorageProvider.getPegoutsWaitingForConfirmations();

        Assertions.assertNotNull(pegoutsWaitingForConfirmations);
        Assertions.assertEquals(0, pegoutsWaitingForConfirmations.getEntries().size());

        SortedMap<Keccak256, BtcTransaction> signatures = bridgeStorageProvider.getPegoutsWaitingForSignatures();

        Assertions.assertNotNull(signatures);
        Assertions.assertTrue(signatures.isEmpty());

        List<UTXO> utxos = federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork);

        Assertions.assertNotNull(utxos);
        Assertions.assertTrue(utxos.isEmpty());
    }

    @Test
    void createSaveAndRecreateInstance() throws IOException {
        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );
        provider0.getReleaseRequestQueue();
        provider0.getPegoutsWaitingForConfirmations();
        provider0.getPegoutsWaitingForSignatures();
        provider0.save();

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(track);
        federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork);
        federationStorageProvider.getOldFederationBtcUTXOs();
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);

        track.commit();

        track = repository.startTracking();

        assertTrue(repository.isContract(bridgeAddress));
        assertNotNull(repository.getStorageBytes(bridgeAddress, RELEASE_REQUEST_QUEUE.getKey()));
        assertNotNull(repository.getStorageBytes(bridgeAddress, PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()));
        assertNotNull(repository.getStorageBytes(bridgeAddress, PEGOUTS_WAITING_FOR_SIGNATURES.getKey()));
        assertNotNull(repository.getStorageBytes(bridgeAddress, NEW_FEDERATION_BTC_UTXOS_KEY.getKey()));
        assertNotNull(repository.getStorageBytes(bridgeAddress, OLD_FEDERATION_BTC_UTXOS_KEY.getKey()));

        BridgeStorageProvider provider = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        ReleaseRequestQueue releaseRequestQueue = provider.getReleaseRequestQueue();

        assertNotNull(releaseRequestQueue);
        assertEquals(0, releaseRequestQueue.getEntries().size());

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = provider.getPegoutsWaitingForConfirmations();

        assertNotNull(pegoutsWaitingForConfirmations);
        assertEquals(0, pegoutsWaitingForConfirmations.getEntries().size());

        SortedMap<Keccak256, BtcTransaction> signatures = provider.getPegoutsWaitingForSignatures();

        assertNotNull(signatures);
        assertTrue(signatures.isEmpty());

        federationStorageProvider = createFederationStorageProvider(track);
        List<UTXO> newUtxos = federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork);

        assertNotNull(newUtxos);
        assertTrue(newUtxos.isEmpty());

        List<UTXO> oldUtxos = federationStorageProvider.getOldFederationBtcUTXOs();

        assertNotNull(oldUtxos);
        assertTrue(oldUtxos.isEmpty());
    }

    @Test
    void createSaveAndRecreateInstanceWithProcessedHashes() throws IOException {
        Sha256Hash hash1 = PegTestUtils.createHash();
        Sha256Hash hash2 = PegTestUtils.createHash();

        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );
        provider0.setHeightBtcTxhashAlreadyProcessed(hash1, 1L);
        provider0.setHeightBtcTxhashAlreadyProcessed(hash2, 1L);
        provider0.save();
        track.commit();

        track = repository.startTracking();

        BridgeStorageProvider provider = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        Assertions.assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(hash1).isPresent());
        Assertions.assertTrue(provider.getHeightIfBtcTxhashIsAlreadyProcessed(hash2).isPresent());
    }

    @Test
    void createSaveAndRecreateInstanceWithTxsWaitingForSignatures() throws IOException {
        BtcTransaction tx1 = createTransaction();
        BtcTransaction tx2 = createTransaction();
        BtcTransaction tx3 = createTransaction();
        Keccak256 hash1 = PegTestUtils.createHash3(1);
        Keccak256 hash2 = PegTestUtils.createHash3(2);
        Keccak256 hash3 = PegTestUtils.createHash3(3);

        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );
        provider0.getPegoutsWaitingForSignatures().put(hash1, tx1);
        provider0.getPegoutsWaitingForSignatures().put(hash2, tx2);
        provider0.getPegoutsWaitingForSignatures().put(hash3, tx3);

        provider0.save();
        track.commit();

        track = repository.startTracking();

        BridgeStorageProvider provider = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        SortedMap<Keccak256, BtcTransaction> signatures = provider.getPegoutsWaitingForSignatures();

        Assertions.assertNotNull(signatures);

        Assertions.assertTrue(signatures.containsKey(hash1));
        Assertions.assertTrue(signatures.containsKey(hash2));
        Assertions.assertTrue(signatures.containsKey(hash3));

        Assertions.assertEquals(tx1.getHash(), signatures.get(hash1).getHash());
        Assertions.assertEquals(tx2.getHash(), signatures.get(hash2).getHash());
        Assertions.assertEquals(tx3.getHash(), signatures.get(hash3).getHash());
    }

    @Test
    void createSaveAndRecreateInstanceWithUTXOS() throws IOException {
        Sha256Hash hash1 = PegTestUtils.createHash(1);
        Sha256Hash hash2 = PegTestUtils.createHash(2);

        Repository repository = createRepository();
        Repository track = repository.startTracking();

        // Federation is the genesis federation ATM
        Federation genesisFederation = FederationTestUtils.getGenesisFederation(bridgeTestnetInstance.getFederationConstants());

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(track);

        federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork).add(new UTXO(hash1, 1, Coin.COIN, 0, false, ScriptBuilder.createOutputScript(genesisFederation.getAddress())));
        federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork).add(new UTXO(hash2, 2, Coin.FIFTY_COINS, 0, false, ScriptBuilder.createOutputScript(genesisFederation.getAddress())));

        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
        track.commit();

        track = repository.startTracking();

        federationStorageProvider = createFederationStorageProvider(track);
        List<UTXO> utxos = federationStorageProvider.getNewFederationBtcUTXOs(testnetBtcParams, activationsBeforeFork);

        assertEquals(utxos.get(0).getHash(), hash1);
        assertEquals(utxos.get(1).getHash(), hash2);
    }

    @Test
    void getNewFederation_initialVersion() {
        List<Integer> storageCalls = new ArrayList<>();
        List<Integer> deserializeCalls = new ArrayList<>();
        Federation newFederation = buildMockFederation(100, 200, 300);
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(NEW_FEDERATION_FORMAT_VERSION.getKey(), address);
                return new byte[0];
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                return new byte[]{(byte) 0xaa};
            }
        });

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(any(byte[].class), any(NetworkParameters.class))).then((InvocationOnMock invocation) -> {
                deserializeCalls.add(0);
                byte[] data = invocation.getArgument(0);
                NetworkParameters networkParametersReceived = invocation.getArgument(1);

                // Make sure we're deserializing what just came from the repo with the correct BTC context
                assertArrayEquals(new byte[]{(byte) 0xaa}, data);
                Assertions.assertEquals(networkParametersReceived, testnetBtcParams);
                return newFederation;
            });

            Assertions.assertEquals(newFederation, federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            Assertions.assertEquals(newFederation, federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            Assertions.assertEquals(2, storageCalls.size());
            Assertions.assertEquals(1, deserializeCalls.size());
        }
    }

    @Test
    void getNewFederation_initialVersion_nullBytes() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class, CALLS_REAL_METHODS)) {
            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    Assertions.assertEquals(NEW_FEDERATION_FORMAT_VERSION.getKey(), address);
                    // First call is storage version getter
                    return new byte[0];
                } else {
                    // Second and third calls are actual storage getters
                    Assertions.assertTrue(storageCalls.size() == 2 || storageCalls.size() == 3);
                    Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            assertNull(federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            Assertions.assertEquals(3, storageCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(any(byte[].class), any(NetworkParameters.class)), never());
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederation(any(byte[].class), any(NetworkParameters.class)), never());
        }
    }

    @Test
    void getNewFederation_multiKeyVersion() {
        Federation newFederation = buildMockFederation(100, 200, 300);
        testGetNewFederationPostMultiKey(newFederation);
    }

    @Test
    void getNewFederation_non_standard_erp_and_p2sh_erp_feds() {
        ActivationConfig.ForBlock activations = ActivationConfigsForTest.iris300().forBlock(0);
        Federation newFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = newFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);
        ErpFederation p2shErpFederation = FederationFactory.buildP2shErpFederation(federationArgs, erpPubKeys, activationDelay);

        testGetNewFederationPostMultiKey(nonStandardErpFederation);
        testGetNewFederationPostMultiKey(p2shErpFederation);
    }

    @Test
    void getNewFederation_multiKeyVersion_nullBytes() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class, CALLS_REAL_METHODS)) {
            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);

            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(
                any(RskAddress.class),
                any(DataWord.class))
            ).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    Assertions.assertEquals(NEW_FEDERATION_FORMAT_VERSION.getKey(), address);
                    // First call is storage version getter
                    return RLP.encodeBigInteger(BigInteger.valueOf(1234));
                } else {
                    // Second and third calls are the actual storage getters
                    Assertions.assertTrue(storageCalls.size() == 2 || storageCalls.size() == 3);
                    Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            assertNull(federationStorageProvider.getNewFederation(federationTestnetConstants, activationsBeforeFork));
            assertEquals(3, storageCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(
                    any(byte[].class),
                    any(NetworkParameters.class)),
                never()
            );
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederation(
                    any(byte[].class),
                    any(NetworkParameters.class)),
                never()
            );
        }
    }

    @Test
    void saveNewFederation_preMultikey() {
        Federation newFederation = buildMockFederation(100, 200, 300);
        List<Integer> storageBytesCalls = new ArrayList<>();
        List<Integer> serializeCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeFederationOnlyBtcKeys(any(Federation.class)))
                .then((InvocationOnMock invocation) -> {
                    Federation federation = invocation.getArgument(0);
                    assertEquals(newFederation, federation);
                    serializeCalls.add(0);
                    return new byte[]{(byte) 0xbb};
                });

            doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);
                // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd},
                        contractAddress.getBytes());
                Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                assertArrayEquals(new byte[]{(byte) 0xbb}, data);
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            // Shouldn't have tried to save nor serialize anything
            assertEquals(0, storageBytesCalls.size());
            assertEquals(0, serializeCalls.size());
            federationStorageProvider.setNewFederation(newFederation);
            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            assertEquals(1, storageBytesCalls.size());
            assertEquals(1, serializeCalls.size());
        }
    }

    @Test
    void saveNewFederation_postMultiKey() {
        ActivationConfig.ForBlock activations = ActivationConfigsForTest.papyrus200().forBlock(0);

        Federation newFederation = buildMockFederation(100, 200, 300);
        testSaveNewFederationPostMultiKey(newFederation, STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, activations);
    }

    @Test
    void saveNewFederation_postMultiKey_RSKIP_201_active_non_standard_erp_fed() {
        ActivationConfig.ForBlock activations = ActivationConfigsForTest.iris300().forBlock(0);
        Federation newFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = newFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);

        testSaveNewFederationPostMultiKey(nonStandardErpFederation, NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, activations);
    }

    @Test
    void saveNewFederation_postMultiKey_RSKIP_353_active_p2sh_erp_fed() {
        Federation newFederation = buildMockFederation(100, 200, 300);
        BridgeConstants bridgeConstants = bridgeTestnetInstance;

        FederationArgs federationArgs = newFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation p2shErpFederation = FederationFactory.buildP2shErpFederation(federationArgs, erpPubKeys, activationDelay);

        testSaveNewFederationPostMultiKey(p2shErpFederation, P2SH_ERP_FEDERATION_FORMAT_VERSION, activationsAllForks);
    }

    @Test
    void getOldFederation_initialVersion() {
        List<Integer> storageCalls = new ArrayList<>();
        List<Integer> deserializeCalls = new ArrayList<>();
        Federation oldFederation = buildMockFederation(100, 200, 300);
        Repository repositoryMock = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                return new byte[0];
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                return new byte[]{(byte) 0xaa};
            }
        });

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(any(byte[].class), any(NetworkParameters.class))).then((InvocationOnMock invocation) -> {
                deserializeCalls.add(0);
                byte[] data = invocation.getArgument(0);
                NetworkParameters networkParametersReceived = invocation.getArgument(1);
                
                // Make sure we're deserializing what just came from the repo with the correct BTC context
                assertArrayEquals(new byte[]{(byte) 0xaa}, data);
                assertEquals(networkParametersReceived, testnetBtcParams);
                return oldFederation;
            });

            Assertions.assertEquals(oldFederation, federationStorageProvider.getOldFederation(federationTestnetConstants, activationsBeforeFork));
            Assertions.assertEquals(2, storageCalls.size());
            Assertions.assertEquals(1, deserializeCalls.size());
        }
    }

    @Test
    void getOldFederation_initialVersion_nullBytes() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class, CALLS_REAL_METHODS)) {
            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                    // First call is storage version getter
                    return new byte[0];
                } else {
                    // Second and third calls are actual storage getters
                    Assertions.assertTrue(storageCalls.size() == 2 || storageCalls.size() == 3);
                    Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getOldFederation(federationTestnetConstants, activationsBeforeFork));
            assertNull(federationStorageProvider.getOldFederation(federationTestnetConstants, activationsBeforeFork));
            Assertions.assertEquals(3, storageCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(any(byte[].class), any(NetworkParameters.class)), never());
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederation(any(byte[].class), any(NetworkParameters.class)), never());
        }
    }

    @Test
    void getOldFederation_multiKeyVersion() {
        Federation oldFederation = buildMockFederation(100, 200, 300);
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        testGetOldFederation(oldFederation, activations);
    }

    @Test
    void getOldFederation_non_standard_erp_feds() {
        Federation oldFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = oldFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        // this should get non-standard hardcoded fed
        ActivationConfig.ForBlock activations = ActivationConfigsForTest.iris300().forBlock(0);
        ErpFederation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);
        testGetOldFederation(nonStandardErpFederation, activations);

        // this should get non-standard with csv unsigned BE fed
        nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);
        testGetOldFederation(nonStandardErpFederation, activations);

        // this should get non-standard fed
        activations = ActivationConfigsForTest.hop400().forBlock(0);
        nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);
        testGetOldFederation(nonStandardErpFederation, activations);
    }

    @Test
    void getOldFederation_RSKIP_353_active_p2sh_erp_fed() {
        Federation oldFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = oldFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation p2shErpFederation = FederationFactory.buildP2shErpFederation(federationArgs, erpPubKeys, activationDelay);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        testGetOldFederation(p2shErpFederation, activations);
    }

    @Test
    void getOldFederation_multiKeyVersion_nullBytes() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);

            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                    // First call is storage version getter
                    return RLP.encodeBigInteger(BigInteger.valueOf(1234));
                } else {
                    // Second and third calls are actual storage getters
                    Assertions.assertTrue(storageCalls.size() == 2 || storageCalls.size() == 3);
                    Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getOldFederation(federationTestnetConstants, activationsBeforeFork));
            assertNull(federationStorageProvider.getOldFederation(federationTestnetConstants, activationsBeforeFork));
            assertEquals(3, storageCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederationOnlyBtcKeys(
                    any(byte[].class),
                    any(NetworkParameters.class)),
                never()
            );
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.deserializeStandardMultisigFederation(
                    any(byte[].class),
                    any(NetworkParameters.class)),
                never()
            );
        }
    }

    @Test
    void saveOldFederation_preMultikey() {
        Federation oldFederation = buildMockFederation(100, 200, 300);
        List<Integer> storageBytesCalls = new ArrayList<>();
        List<Integer> serializeCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeFederationOnlyBtcKeys(
                any(Federation.class)
            )).then((InvocationOnMock invocation) -> {
                Federation federation = invocation.getArgument(0);
                Assertions.assertEquals(oldFederation, federation);
                serializeCalls.add(0);
                return new byte[]{(byte) 0xbb};
            });
            doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);
                // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
                Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                assertArrayEquals(new byte[]{(byte) 0xbb}, data);
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            // Shouldn't have tried to save nor serialize anything
            assertEquals(0, storageBytesCalls.size());
            assertEquals(0, serializeCalls.size());
            federationStorageProvider.setOldFederation(oldFederation);
            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            assertEquals(1, storageBytesCalls.size());
            assertEquals(1, serializeCalls.size());
        }
    }

    @Test
    void saveOldFederation_postMultikey() {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP123)).thenReturn(true);

        Federation oldFederation = buildMockFederation(100, 200, 300);
        testSaveOldFederation(oldFederation, STANDARD_MULTISIG_FEDERATION_FORMAT_VERSION, activations);
    }

    @Test
    void saveOldFederation_postMultikey_RSKIP_201_active_non_standard_erp_fed() {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP123)).thenReturn(true);
        when(activations.isActive(ConsensusRule.RSKIP201)).thenReturn(true);

        Federation oldFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = oldFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation nonStandardErpFederation = FederationFactory.buildNonStandardErpFederation(federationArgs, erpPubKeys, activationDelay, activations);
        testSaveOldFederation(nonStandardErpFederation, NON_STANDARD_ERP_FEDERATION_FORMAT_VERSION, activations);
    }

    @Test
    void saveOldFederation_postMultikey_RSKIP_353_active_p2sh_erp_fed() {
        Federation oldFederation = buildMockFederation(100, 200, 300);

        FederationArgs federationArgs = oldFederation.getArgs();
        List<BtcECKey> erpPubKeys = federationTestnetConstants.getErpFedPubKeysList();
        long activationDelay = federationTestnetConstants.getErpFedActivationDelay();

        ErpFederation p2shErpFederation = FederationFactory.buildP2shErpFederation(federationArgs, erpPubKeys, activationDelay);
        testSaveOldFederation(p2shErpFederation, P2SH_ERP_FEDERATION_FORMAT_VERSION, activationsAllForks);
    }

    @Test
    void saveOldFederation_preMultikey_setToNull() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            List<Integer> storageBytesCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            Mockito.doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);

                // Make sure the bytes are set to the correct address in the repo and that what's saved is null
                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                assertNull(data);
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any());

            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            // Shouldn't have tried to save nor serialize anything
            Assertions.assertEquals(0, storageBytesCalls.size());
            federationStorageProvider.setOldFederation(null);
            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            Assertions.assertEquals(1, storageBytesCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.serializeFederation(any(Federation.class)), never());
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.serializeFederationOnlyBtcKeys(any(Federation.class)), never());
        }
    }

    @Test
    void saveOldFederation_postMultikey_setToNull() {
        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class, CALLS_REAL_METHODS)) {
            List<Integer> storageBytesCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            Mockito.doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);

                if (storageBytesCalls.size() == 1) {
                    // First call is the version setting
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                    Assertions.assertEquals(BigInteger.valueOf(1000), RLP.decodeBigInteger(data, 0));
                } else {
                    Assertions.assertEquals(2, storageBytesCalls.size());
                    // Make sure the bytes are set to the correct address in the repo and that what's saved is null
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                    assertNull(data);
                }
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any());

            federationStorageProvider.save(testnetBtcParams, activationsAllForks);
            // Shouldn't have tried to save nor serialize anything
            Assertions.assertEquals(0, storageBytesCalls.size());
            federationStorageProvider.setOldFederation(null);
            federationStorageProvider.save(testnetBtcParams, activationsAllForks);
            Assertions.assertEquals(2, storageBytesCalls.size());

            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.serializeFederation(any(Federation.class)), never());
            bridgeSerializationUtilsMocked.verify(() -> BridgeSerializationUtils.serializeFederationOnlyBtcKeys(any(Federation.class)), never());
        }
    }

    @Test
    void getPendingFederation_initialVersion() {
        List<Integer> storageCalls = new ArrayList<>();
        List<Integer> deserializeCalls = new ArrayList<>();
        PendingFederation pendingFederation = buildMockPendingFederation(100, 200, 300);
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                return new byte[0];
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
                return new byte[]{(byte) 0xaa};
            }
        });

        try (MockedStatic<PendingFederation> pendingFederationMocked = mockStatic(PendingFederation.class)) {
            pendingFederationMocked.when(() -> PendingFederation.deserializeFromBtcKeysOnly(any(byte[].class))).then((InvocationOnMock invocation) -> {
                deserializeCalls.add(0);
                byte[] data = invocation.getArgument(0);
                // Make sure we're deserializing what just came from the repo with the correct BTC context
                assertArrayEquals(new byte[]{(byte) 0xaa}, data);
                return pendingFederation;
            });

            Assertions.assertEquals(pendingFederation, federationStorageProvider.getPendingFederation());
            Assertions.assertEquals(2, storageCalls.size());
            Assertions.assertEquals(1, deserializeCalls.size());
        }
    }

    @Test
    void getPendingFederation_initialVersion_nullBytes() {
        try (MockedStatic<PendingFederation> pendingFederationMocked = mockStatic(PendingFederation.class)) {

            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    // First call is storage version getter
                    Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                    return new byte[0];
                } else {
                    // Second call is the actual storage getter
                    Assertions.assertEquals(2, storageCalls.size());
                    Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getPendingFederation());
            Assertions.assertEquals(2, storageCalls.size());

            pendingFederationMocked.verify(() -> PendingFederation.deserialize(any(byte[].class)), never());
        }
    }

    @Test
    void getPendingFederation_multiKeyVersion() {
        List<Integer> storageCalls = new ArrayList<>();
        List<Integer> deserializeCalls = new ArrayList<>();
        PendingFederation pendingFederation = buildMockPendingFederation(100, 200, 300);
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                return RLP.encodeBigInteger(BigInteger.valueOf(1234));
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
                return new byte[]{(byte) 0xaa};
            }
        });

        try (MockedStatic<PendingFederation> pendingFederationMocked = mockStatic(PendingFederation.class)) {
            pendingFederationMocked.when(() -> PendingFederation.deserialize(any(byte[].class))).then((InvocationOnMock invocation) -> {
                deserializeCalls.add(0);
                byte[] data = invocation.getArgument(0);
                // Make sure we're deserializing what just came from the repo with the correct BTC context
                assertArrayEquals(new byte[]{(byte) 0xaa}, data);
                return pendingFederation;
            });

            Assertions.assertEquals(pendingFederation, federationStorageProvider.getPendingFederation());
            Assertions.assertEquals(2, storageCalls.size());
            Assertions.assertEquals(1, deserializeCalls.size());
        }
    }

    @Test
    void getPendingFederation_multiKeyVersion_nullBytes() {
        try (MockedStatic<PendingFederation> pendingFederationMocked = mockStatic(PendingFederation.class)) {
            List<Integer> storageCalls = new ArrayList<>();
            Repository repositoryMock = mock(Repository.class);
            FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

            when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
                storageCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);

                assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

                if (storageCalls.size() == 1) {
                    // First call is storage version getter
                    Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                    return RLP.encodeBigInteger(BigInteger.valueOf(1234));
                } else {
                    // Second call is the actual storage getter
                    Assertions.assertEquals(2, storageCalls.size());
                    Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
                    return null;
                }
            });

            assertNull(federationStorageProvider.getPendingFederation());
            assertEquals(2, storageCalls.size());
            pendingFederationMocked.verify(() -> PendingFederation.deserialize(any(byte[].class)), never());
        }
    }

    @Test
    void savePendingFederation_preMultikey() throws IOException {
        PendingFederation pendingFederation = buildMockPendingFederation(100, 200, 300);
        List<Integer> storageBytesCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        doAnswer((InvocationOnMock invocation) -> {
            storageBytesCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
            assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
            Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
            return null;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
        // Shouldn't have tried to save anything since pending federation is not set
        assertEquals(0, storageBytesCalls.size());

        federationStorageProvider.setPendingFederation(pendingFederation);
        // Should save the pending federation because is now set
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
        // Should have called storage one time
        assertEquals(1, storageBytesCalls.size());
    }

    @Test
    void savePendingFederation_preMultikey_setToNull() throws IOException {
        List<Integer> storageBytesCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            storageBytesCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);
            byte[] data = invocation.getArgument(2);
            // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
            assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
            Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
            assertNull(data);
            return null;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any());

        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
        // Shouldn't have tried to save nor serialize anything
        Assertions.assertEquals(0, storageBytesCalls.size());
        federationStorageProvider.setPendingFederation(null);
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
        Assertions.assertEquals(1, storageBytesCalls.size());
    }

    @Test
    void savePendingFederation_postMultikey() throws IOException {
        PendingFederation pendingFederation = buildMockPendingFederation(100, 200, 300);
        List<Integer> storageBytesCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            storageBytesCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);
            byte[] data = invocation.getArgument(2);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageBytesCalls.size() == 1) {
                Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                Assertions.assertEquals(BigInteger.valueOf(1000), RLP.decodeBigInteger(data, 0));
            } else {
                Assertions.assertEquals(2, storageBytesCalls.size());
                // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
            }
            return null;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        // Shouldn't have tried to save anything since pending federation is not set
        Assertions.assertEquals(0, storageBytesCalls.size());

        federationStorageProvider.setPendingFederation(pendingFederation);
        // Should save the pending federation because is now set
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        // Should have called storage two times since RSKIP123 is activated
        Assertions.assertEquals(2, storageBytesCalls.size());
    }

    @Test
    void savePendingFederation_postMultikey_setToNull() throws IOException {
        List<Integer> storageBytesCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        Mockito.doAnswer((InvocationOnMock invocation) -> {
            storageBytesCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);
            byte[] data = invocation.getArgument(2);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageBytesCalls.size() == 1) {
                Assertions.assertEquals(PENDING_FEDERATION_FORMAT_VERSION.getKey(), address);
                Assertions.assertEquals(BigInteger.valueOf(1000), RLP.decodeBigInteger(data, 0));
            } else {
                Assertions.assertEquals(2, storageBytesCalls.size());
                // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                Assertions.assertEquals(PENDING_FEDERATION_KEY.getKey(), address);
                assertNull(data);
            }
            return null;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any());

        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        // Shouldn't have tried to save nor serialize anything
        Assertions.assertEquals(0, storageBytesCalls.size());
        federationStorageProvider.setPendingFederation(null);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        Assertions.assertEquals(2, storageBytesCalls.size());
    }

    @Test
    void getFederationElection_nonNullBytes() {
        List<Integer> calls = new ArrayList<>();
        AddressBasedAuthorizer authorizerMock = mock(AddressBasedAuthorizer.class);
        ABICallElection electionMock = mock(ABICallElection.class);
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            calls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);
            // Make sure the bytes are got from the correct address in the repo
            assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
            Assertions.assertEquals(FEDERATION_ELECTION_KEY.getKey(), address);
            return new byte[]{(byte)0xaa};
        });

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.deserializeElection(any(byte[].class), any(AddressBasedAuthorizer.class))).then((InvocationOnMock invocation) -> {
                calls.add(0);
                byte[] data = invocation.getArgument(0);
                AddressBasedAuthorizer authorizer = invocation.getArgument(1);
                // Make sure we're deserializing what just came from the repo with the correct AddressBasedAuthorizer
                assertArrayEquals(new byte[]{(byte) 0xaa}, data);
                assertEquals(authorizerMock, authorizer);
                return electionMock;
            });

            assertSame(electionMock, federationStorageProvider.getFederationElection(authorizerMock));
            assertEquals(2, calls.size()); // 1 for each call to deserializeFederationOnlyBtcKeys & getStorageBytes
        }
    }

    @Test
    void getFederationElection_nullBytes() {
        List<Integer> calls = new ArrayList<>();
        AddressBasedAuthorizer authorizerMock = mock(AddressBasedAuthorizer.class);
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            calls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);
            // Make sure the bytes are got from the correct address in the repo
            assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
            Assertions.assertEquals(FEDERATION_ELECTION_KEY.getKey(), address);
            return null;
        });

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.deserializeElection(any(byte[].class), any(AddressBasedAuthorizer.class))).then((InvocationOnMock invocation) -> {
                calls.add(0);
                return null;
            });
        }

        ABICallElection result = federationStorageProvider.getFederationElection(authorizerMock);
        Assertions.assertSame(authorizerMock, TestUtils.getInternalState(result, "authorizer"));
        Assertions.assertEquals(0, result.getVotes().size());
        Assertions.assertEquals(1, calls.size()); // getStorageBytes is the only one called (can't be the other way around)
    }

    @Test
    void saveFederationElection() {
        ABICallElection electionMock = mock(ABICallElection.class);
        List<Integer> storageBytesCalls = new ArrayList<>();
        List<Integer> serializeCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeElection(any(ABICallElection.class))).then((InvocationOnMock invocation) -> {
                ABICallElection election = invocation.getArgument(0);
                Assertions.assertSame(electionMock, election);
                serializeCalls.add(0);
                return Hex.decode("aabb");
            });

            Mockito.doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);
                // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                assertArrayEquals(new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd}, contractAddress.getBytes());
                Assertions.assertEquals(FEDERATION_ELECTION_KEY.getKey(), address);
                assertArrayEquals(Hex.decode("aabb"), data);
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            // Shouldn't have tried to save nor serialize anything
            Assertions.assertEquals(0, storageBytesCalls.size());
            Assertions.assertEquals(0, serializeCalls.size());
            TestUtils.setInternalState(federationStorageProvider, "federationElection", electionMock);
            federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);
            Assertions.assertEquals(1, storageBytesCalls.size());
            Assertions.assertEquals(1, serializeCalls.size());
        }
    }

    @Test
    void getReleaseRequestQueue_before_rskip_146_activation() throws IOException {
        Repository repositoryMock = mock(Repository.class);
        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams, activationsBeforeFork);

        List<ReleaseRequestQueue.Entry> oldEntriesList = new ArrayList<>(Collections.singletonList(
            new ReleaseRequestQueue.Entry(
                Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
                Coin.COIN)));

        when(repositoryMock.getStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE.getKey())))
                .then((InvocationOnMock invocation) ->
                        BridgeSerializationUtils.serializeReleaseRequestQueue(new ReleaseRequestQueue(oldEntriesList)));

        ReleaseRequestQueue result = storageProvider.getReleaseRequestQueue();

        verify(repositoryMock, never()).getStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey()));

        Assertions.assertEquals(1, result.getEntries().size());
        Assertions.assertTrue(result.getEntries().containsAll(oldEntriesList));
    }

    @Test
    void getReleaseRequestQueue_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        ReleaseRequestQueue.Entry oldEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
            Coin.COIN);

        ReleaseRequestQueue.Entry newEntry = new ReleaseRequestQueue.Entry(
            Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        Repository repositoryMock = mock(Repository.class);

        when(repositoryMock.getStorageBytes(any(),eq(RELEASE_REQUEST_QUEUE.getKey()))).
                thenReturn(BridgeSerializationUtils.serializeReleaseRequestQueue(new ReleaseRequestQueue(new ArrayList<>(Arrays.asList(oldEntry)))));

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams,
            activations);

        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0));

        ReleaseRequestQueue result = storageProvider.getReleaseRequestQueue();

        Assertions.assertEquals(2, result.getEntries().size());
        Assertions.assertEquals(result.getEntries().get(0), oldEntry);
        Assertions.assertEquals(result.getEntries().get(1), newEntry);
    }

    @Test
    void saveReleaseRequestQueue_before_rskip_146_activation() throws IOException {
        Repository repositoryMock = mock(Repository.class);
        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams, activationsBeforeFork);

        List<ReleaseRequestQueue.Entry> oldEntriesList = new ArrayList<>(Collections.singletonList(
            new ReleaseRequestQueue.Entry(
                Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
                Coin.COIN)));

        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();
        releaseRequestQueue.add(Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
            Coin.COIN);

        doAnswer((i) -> {
            List<ReleaseRequestQueue.Entry> entries = BridgeSerializationUtils.deserializeReleaseRequestQueue(i.getArgument(2), testnetBtcParams);
            Assertions.assertEquals(oldEntriesList, entries);
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE.getKey()), any(byte[].class));

        storageProvider.saveReleaseRequestQueue();

        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE.getKey()), any(byte[].class));
        verify(repositoryMock, never()).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey()), any(byte[].class));
    }

    @Test
    void saveReleaseRequestQueue_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        ReleaseRequestQueue.Entry newEntry =
            new ReleaseRequestQueue.Entry(
                Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
                Coin.COIN,
                PegTestUtils.createHash3(0)
            );

        ReleaseRequestQueue.Entry oldEntry =
            new ReleaseRequestQueue.Entry(
                Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
                Coin.COIN
            );

        Repository repositoryMock = mock(Repository.class);
        when(repositoryMock.getStorageBytes(any(),eq(RELEASE_REQUEST_QUEUE.getKey()))).
                thenReturn(BridgeSerializationUtils.serializeReleaseRequestQueue(new ReleaseRequestQueue(new ArrayList<>(Arrays.asList(oldEntry)))));

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams, activations);
        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0)
        );

        doAnswer((i) -> {
            List<ReleaseRequestQueue.Entry> entries = BridgeSerializationUtils.deserializeReleaseRequestQueue(i.getArgument(2), testnetBtcParams);
            Assertions.assertEquals(entries, new ArrayList<>(Arrays.asList(oldEntry)));
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE.getKey()), any(byte[].class));

        doAnswer((i) -> {
            List<ReleaseRequestQueue.Entry> entries = BridgeSerializationUtils.deserializeReleaseRequestQueue(i.getArgument(2), testnetBtcParams, true);
            Assertions.assertEquals(entries, new ArrayList<>(Arrays.asList(newEntry)));
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey()), any(byte[].class));

        storageProvider.saveReleaseRequestQueue();

        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE.getKey()), any(byte[].class));
        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(RELEASE_REQUEST_QUEUE_WITH_TXHASH.getKey()), any(byte[].class));
        Assertions.assertEquals(2, storageProvider.getReleaseRequestQueue().getEntries().size());
    }

    @Test
    void getPegoutsWaitingForConfirmations_before_rskip_146_activation() throws IOException {
        Repository repositoryMock = mock(Repository.class);
        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"),
            testnetBtcParams, activationsBeforeFork);

        Set<PegoutsWaitingForConfirmations.Entry> oldEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(new BtcTransaction(testnetBtcParams), 1L)
        ));

        when(repositoryMock.getStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey())))
                .then((InvocationOnMock invocation) ->
                        BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(new PegoutsWaitingForConfirmations(oldEntriesSet)));

        PegoutsWaitingForConfirmations result = storageProvider.getPegoutsWaitingForConfirmations();

        verify(repositoryMock, never()).getStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey()));

        Assertions.assertEquals(1, result.getEntries().size());
        Assertions.assertTrue(result.getEntries().containsAll(oldEntriesSet));
    }

    @Test
    void getPegoutsWaitingForConfirmations_after_rskip_146_activation() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        Set<PegoutsWaitingForConfirmations.Entry> oldEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(new BtcTransaction(testnetBtcParams), 1L)
        ));

        Repository repositoryMock = mock(Repository.class);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey())))
                .thenReturn(BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(new PegoutsWaitingForConfirmations(oldEntriesSet)));

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"),
            testnetBtcParams, activations);

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = storageProvider.getPegoutsWaitingForConfirmations();

        pegoutsWaitingForConfirmations.add(new SimpleBtcTransaction(testnetBtcParams, PegTestUtils.createHash(0)),
            1L,
            PegTestUtils.createHash3(0));

        PegoutsWaitingForConfirmations result = storageProvider.getPegoutsWaitingForConfirmations();

        Assertions.assertEquals(2, result.getEntries().size());
    }

    @Test
    void savePegoutsWaitingForConfirmations_before_rskip_146_activations() throws IOException {
        Repository repositoryMock = mock(Repository.class);
        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams, activationsBeforeFork);

        Set<PegoutsWaitingForConfirmations.Entry> oldEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(new BtcTransaction(testnetBtcParams), 1L)
        ));

        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = storageProvider.getPegoutsWaitingForConfirmations();
        pegoutsWaitingForConfirmations.add(new BtcTransaction(testnetBtcParams), 1L);

        doAnswer((i) -> {
            Set<PegoutsWaitingForConfirmations.Entry> entries = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(i.getArgument(2), testnetBtcParams).getEntries();
            Assertions.assertEquals(oldEntriesSet, entries);
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()), any(byte[].class));

        storageProvider.savePegoutsWaitingForConfirmations();

        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()), any(byte[].class));
        verify(repositoryMock, never()).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey()), any(byte[].class));
    }

    @Test
    void savePegoutsWaitingForConfirmations_after_rskip_146_activations() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        Set<PegoutsWaitingForConfirmations.Entry> newEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(new BtcTransaction(testnetBtcParams), 1L, PegTestUtils.createHash3(0))
        ));

        Set<PegoutsWaitingForConfirmations.Entry> oldEntriesSet = new HashSet<>(Collections.singletonList(
            new PegoutsWaitingForConfirmations.Entry(new BtcTransaction(testnetBtcParams), 1L)
        ));

        Repository repositoryMock = mock(Repository.class);

        when(repositoryMock.getStorageBytes(any(),eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()))).
                thenReturn(BridgeSerializationUtils.serializePegoutsWaitingForConfirmations(new PegoutsWaitingForConfirmations(oldEntriesSet)));

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(repositoryMock, mockAddress("aabbccdd"), testnetBtcParams, activations);
        PegoutsWaitingForConfirmations pegoutsWaitingForConfirmations = storageProvider.getPegoutsWaitingForConfirmations();

        pegoutsWaitingForConfirmations.add(new SimpleBtcTransaction(testnetBtcParams, PegTestUtils.createHash(1)),
            1L,
            PegTestUtils.createHash3(0));

        doAnswer((i) -> {
            Set<PegoutsWaitingForConfirmations.Entry> entries = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(i.getArgument(2), testnetBtcParams).getEntries();
            Assertions.assertEquals(entries, oldEntriesSet);
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()), any(byte[].class));

        doAnswer((i) -> {
            Set<PegoutsWaitingForConfirmations.Entry> entries = BridgeSerializationUtils.deserializePegoutsWaitingForConfirmations(i.getArgument(2), testnetBtcParams, true).getEntries();
            Assertions.assertEquals(entries, newEntriesSet);
            return true;
        }).when(repositoryMock).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey()), any(byte[].class));

        storageProvider.savePegoutsWaitingForConfirmations();

        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS.getKey()), any(byte[].class));
        verify(repositoryMock, atLeastOnce()).addStorageBytes(any(RskAddress.class), eq(PEGOUTS_WAITING_FOR_CONFIRMATIONS_WITH_TXHASH_KEY.getKey()), any(byte[].class));
        Assertions.assertEquals(2, storageProvider.getPegoutsWaitingForConfirmations().getEntries().size());
    }

    @Test
    void getReleaseTransaction_after_rskip_146_activations() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP146)).thenReturn(true);

        BtcTransaction tx1 = createTransaction();
        BtcTransaction tx2 = createTransaction();
        BtcTransaction tx3 = createTransaction();

        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider0.getPegoutsWaitingForConfirmations().add(tx1, 1L, PegTestUtils.createHash3(0));
        provider0.getPegoutsWaitingForConfirmations().add(tx2, 2L, PegTestUtils.createHash3(1));
        provider0.getPegoutsWaitingForConfirmations().add(tx3, 3L, PegTestUtils.createHash3(2));

        provider0.save();

        track.commit();

        //Reusing same storage configuration as the height doesn't affect storage configurations for releases.
        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Assertions.assertEquals(3, provider.getPegoutsWaitingForConfirmations().getEntries().size());
        Assertions.assertEquals(0, provider.getPegoutsWaitingForSignatures().size());
    }

    @Test
    void setLockingCap_before_fork() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsBeforeFork
        );

        provider0.setLockingCap(Coin.ZERO);
        provider0.saveLockingCap();

        // If the network upgrade is not enabled we shouldn't be writing in the repository
        verify(repository, never()).addStorageBytes(any(), any(), any());
    }

    @Test
    void setLockingCap_after_fork() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        provider0.setLockingCap(Coin.ZERO);
        provider0.saveLockingCap();

        // Once the network upgrade is active, we will store the locking cap in the repository
        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            LOCKING_CAP_KEY.getKey(),
            BridgeSerializationUtils.serializeCoin(Coin.ZERO)
        );
    }

    @Test
    void getLockingCap_before_fork() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        assertNull(provider0.getLockingCap());

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, never()).getStorageBytes(bridgeAddress, LOCKING_CAP_KEY.getKey());
    }

    @Test
    void getLockingCap_after_fork() {
        Repository repository = mock(Repository.class);
        // If by chance the repository is called I want to force the tests to fail
        when(repository.getStorageBytes(bridgeAddress, LOCKING_CAP_KEY.getKey())).thenReturn(new byte[] { 1 });

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        assertEquals(Coin.SATOSHI, provider0.getLockingCap());

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, atLeastOnce()).getStorageBytes(bridgeAddress, LOCKING_CAP_KEY.getKey());
    }

    @Test
    void setLockingCapAndGetLockingCap() {
        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        Coin expectedCoin = Coin.valueOf(666);

        // We store the locking cap
        provider0.setLockingCap(expectedCoin);
        provider0.saveLockingCap();
        track.commit();

        track = repository.startTracking();

        BridgeStorageProvider provider = new BridgeStorageProvider(
            track,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        // And then we get it back
        assertEquals(expectedCoin, provider.getLockingCap());
    }

    @Test
    void getHeightIfBtcTxhashIsAlreadyProcessed_before_RSKIP134_does_not_use_new_storage() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        HashMap<Sha256Hash, Long> hashes = new HashMap<>();
        hashes.put(hash, 1L);
        when(repository.getStorageBytes(
            bridgeAddress,
            BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeMapOfHashesToLong(hashes));

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());

        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());
        verify(repository, never()).getStorageBytes(bridgeAddress, BTC_TX_HASH_AP.getCompoundKey("-", hash.toString()));
    }

    @Test
    void getHeightIfBtcTxhashIsAlreadyProcessed_after_RSKIP134_uses_new_storage()
        throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash1 = Sha256Hash.ZERO_HASH;
        Sha256Hash hash2 = Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001");

        HashMap<Sha256Hash, Long> hashes = new HashMap<>();
        hashes.put(hash1, 1L);
        when(repository.getStorageBytes(
            bridgeAddress,
            BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeMapOfHashesToLong(hashes));

        when(repository.getStorageBytes(
            bridgeAddress,
            BTC_TX_HASH_AP.getCompoundKey("-", hash2.toString())
        )).thenReturn(BridgeSerializationUtils.serializeLong(2L));

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        // Get hash1 which is stored in old storage
        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash1);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());

        // old storage was accessed and new storage not
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());
        verify(repository, never()).getStorageBytes(bridgeAddress, BTC_TX_HASH_AP.getCompoundKey("-", hash2.toString()));

        // Get hash2 which is stored in new storage
        result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash2);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(2), result.get());

        // old storage wasn't accessed anymore (because it is cached) and new storage was accessed
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASH_AP.getCompoundKey("-", hash2.toString()));

        // Get hash2 again
        result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash2);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(2), result.get());

        // No more accesses to repository, as both values are in cache
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASH_AP.getCompoundKey("-", hash2.toString()));
    }

    @Test
    void setHeightBtcTxhashAlreadyProcessed_before_RSKIP134_does_not_use_new_storage() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        provider0.setHeightBtcTxhashAlreadyProcessed(hash, 1L);

        // The repository is accessed once to set the value
        verify(repository, times(1)).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());
    }

    @Test
    void setHeightBtcTxhashAlreadyProcessed_before_RSKIP134_uses_new_storage() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        provider0.setHeightBtcTxhashAlreadyProcessed(hash, 1L);

        // The repository is never accessed as the new storage keeps the values in cache until save
        verify(repository, never()).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());
    }

    @Test
    void saveHeightBtcTxHashAlreadyProcessed() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider0 = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        provider0.setHeightBtcTxhashAlreadyProcessed(hash, 1L);

        provider0.saveHeightBtcTxHashAlreadyProcessed();

        // The repository is never accessed as the new storage keeps the values in cache until save
        verify(repository, never()).getStorageBytes(bridgeAddress, BTC_TX_HASHES_ALREADY_PROCESSED_KEY.getKey());

        Optional<Long> result = provider0.getHeightIfBtcTxhashIsAlreadyProcessed(hash);
        assertTrue(result.isPresent());
        assertEquals(Long.valueOf(1), result.get());
    }

    @Test
    void getCoinBaseInformation_before_RSKIP143() {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        CoinbaseInformation result = provider.getCoinbaseInformation(hash);
        assertNull(result);

        verify(repository, never()).getStorageBytes(bridgeAddress, DataWord.fromLongString("coinbaseInformation-" + hash));
    }

    @Test
    void getCoinBaseInformation_after_RSKIP143() {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        when(repository.getStorageBytes(bridgeAddress, DataWord.fromLongString("coinbaseInformation-" + hash)))
            .thenReturn(BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation));

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        CoinbaseInformation result = provider.getCoinbaseInformation(hash);
        assertEquals(coinbaseInformation.getWitnessMerkleRoot(),result.getWitnessMerkleRoot());
    }

    @Test
    void setCoinBaseInformation_before_RSKIP143() {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertNull(provider.getCoinbaseInformation(hash));
    }

    @Test
    void setCoinBaseInformation_after_RSKIP143() {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertEquals(coinbaseInformation, provider.getCoinbaseInformation(hash));
    }

    @Test
    void saveCoinBaseInformation_before_RSKIP143() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertNull(provider.getCoinbaseInformation(hash));

        provider.save();

        verify(repository, never()).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("coinbaseInformation" + hash),
            BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation)
        );
    }

    @Test
    void saveCoinBaseInformation_after_RSKIP143() throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash hash = Sha256Hash.ZERO_HASH;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        assertNull(provider.getCoinbaseInformation(hash));

        CoinbaseInformation coinbaseInformation = new CoinbaseInformation(Sha256Hash.ZERO_HASH);
        provider.setCoinbaseInformation(hash, coinbaseInformation);

        assertEquals(coinbaseInformation, provider.getCoinbaseInformation(hash));

        provider.save();

        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("coinbaseInformation-" + hash),
            BridgeSerializationUtils.serializeCoinbaseInformation(coinbaseInformation)
        );
    }

    @Test
    void getBtcBestBlockHashByHeight_beforeRskip199() {
        Repository repository = mock(Repository.class);
        int blockHeight = 100;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        Optional<Sha256Hash> hashOptional = provider.getBtcBestBlockHashByHeight(blockHeight);

        Assertions.assertFalse(hashOptional.isPresent());
    }

    @Test
    void getBtcBestBlockHashByHeight_afterRskip199_hashNotFound() {
        Repository repository = mock(Repository.class);
        int blockHeight = 100;

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        Optional<Sha256Hash> hashOptional = provider.getBtcBestBlockHashByHeight(blockHeight);

        Assertions.assertFalse(hashOptional.isPresent());
    }

    @Test
    void getBtcBestBlockHashByHeight_afterRskip199() {
        Sha256Hash blockHash = PegTestUtils.createHash(2);
        byte[] serializedHash = BridgeSerializationUtils.serializeSha256Hash(blockHash);

        Repository repository = mock(Repository.class);
        when(repository.getStorageBytes(any(), any())).thenReturn(serializedHash);

        int blockHeight = 100;
        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        Optional<Sha256Hash> hashOptional = provider.getBtcBestBlockHashByHeight(blockHeight);

        Assertions.assertTrue(hashOptional.isPresent());
        Assertions.assertEquals(blockHash, hashOptional.get());
    }

    @Test
    void saveBtcBlocksIndex_beforeRskip199() throws IOException {
        int blockHeight = 100;
        Sha256Hash blockHash = PegTestUtils.createHash(2);
        byte[] serializedHash = BridgeSerializationUtils.serializeSha256Hash(blockHash);

        DataWord storageKey = DataWord.fromLongString("btcBlockHeight-" + blockHeight);

        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsBeforeFork
        );

        provider.setBtcBestBlockHashByHeight(blockHeight, blockHash);
        provider.save();

        verify(repository, never()).addStorageBytes(
            bridgeAddress,
            storageKey,
            serializedHash
        );
    }

    @Test
    void saveBtcBlocksIndex_afterRskip199() throws IOException {
        int blockHeight = 100;
        DataWord storageKey = DataWord.fromLongString("btcBlockHeight-" + blockHeight);

        Sha256Hash blockHash = PegTestUtils.createHash(2);
        byte[] serializedHash = BridgeSerializationUtils.serializeSha256Hash(blockHash);

        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activationsAllForks
        );

        provider.setBtcBestBlockHashByHeight(blockHeight, blockHash);
        provider.save();

        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            storageKey,
            serializedHash
        );
    }

    @Test
    void getActiveFederationCreationBlockHeight_before_fork() {
        Repository repository = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.empty(), federationStorageProvider.getActiveFederationCreationBlockHeight(activationsBeforeFork));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, never()).getStorageBytes(bridgeAddress, ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey());
    }

    @Test
    void getActiveFederationCreationBlockHeight_after_fork() {
        Repository repository = mock(Repository.class);
        // If by chance the repository is called I want to force the tests to fail
        when(repository.getStorageBytes(bridgeAddress, ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey())).thenReturn(new byte[] { 1 });

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.of(1L), federationStorageProvider.getActiveFederationCreationBlockHeight(activationsAllForks));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, atLeastOnce()).getStorageBytes(bridgeAddress, ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey());
    }

    @Test
    void setActiveFederationCreationBlockHeightAndGetActiveFederationCreationBlockHeight() {
        Repository repository = createRepository();
        Repository track = repository.startTracking();

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(track);

        // We store the value
        federationStorageProvider.setActiveFederationCreationBlockHeight(1L);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        track.commit();

        track = repository.startTracking();
        federationStorageProvider = createFederationStorageProvider(track);

        // And then we get it back
        MatcherAssert.assertThat(federationStorageProvider.getActiveFederationCreationBlockHeight(activationsAllForks), is(Optional.of(1L)));
    }

    @Test
    void saveActiveFederationCreationBlockHeight_after_RSKIP186() {
        Repository repository = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        federationStorageProvider.setActiveFederationCreationBlockHeight(10L);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);

        // Once the network upgrade is active, we will store it in the repository
        verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(),
                BridgeSerializationUtils.serializeLong(10L)
        );
    }

    @Test
    void saveActiveFederationCreationBlockHeight_before_RSKIP186() {
        Repository repository = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        federationStorageProvider.setActiveFederationCreationBlockHeight(10L);
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);

        // If the network upgrade is not enabled we shouldn't be saving to the repository
        verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(ACTIVE_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey()),
                any()
        );
    }

    @Test
    void getNextFederationCreationBlockHeight_before_fork() {
        Repository repository = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.empty(), federationStorageProvider.getNextFederationCreationBlockHeight(activationsBeforeFork));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, never()).getStorageBytes(bridgeAddress, NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey());
    }

    @Test
    void getNextFederationCreationBlockHeight_after_fork() {
        Repository repository = mock(Repository.class);
        // If by chance the repository is called I want to force the tests to fail
        when(repository.getStorageBytes(bridgeAddress, NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey())).thenReturn(new byte[] { 1 });
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.of(1L), federationStorageProvider.getNextFederationCreationBlockHeight(activationsAllForks));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, atLeastOnce()).getStorageBytes(bridgeAddress, NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey());
    }

    @Test
    void setNextFederationCreationBlockHeightAndGetNextFederationCreationBlockHeight() {
        Repository repository = createRepository();
        Repository track = repository.startTracking();

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(track);

        // We store the value
        federationStorageProvider.setNextFederationCreationBlockHeight(1L);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        track.commit();

        track = repository.startTracking();
        federationStorageProvider = createFederationStorageProvider(track);

        // And then we get it back
        MatcherAssert.assertThat(federationStorageProvider.getNextFederationCreationBlockHeight(activationsAllForks), is(Optional.of(1L)));
    }

    @Test
    void saveNextFederationCreationBlockHeight_after_RSKIP186() {
        Repository repository1 = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository1);

        federationStorageProvider.setNextFederationCreationBlockHeight(10L);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);

        // Once the network upgrade is active, we will store it in the repository
        verify(repository1, times(1)).addStorageBytes(
                bridgeAddress,
                NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(),
                BridgeSerializationUtils.serializeLong(10L)
        );

        Repository repository2 = mock(Repository.class);
        federationStorageProvider = createFederationStorageProvider(repository2);

        federationStorageProvider.clearNextFederationCreationBlockHeight();
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);

        // Once the network upgrade is active, we will store it in the repository
        verify(repository2, times(1)).addStorageBytes(
                bridgeAddress,
                NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey(),
                null
        );
    }

    @Test
    void isFlyoverFederationDerivationHashUsed_afterRSKIP176_returnTrue() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash))
        ).thenReturn(new byte[]{FAST_BRIDGE_FEDERATION_SCRIPT_HASH_TRUE_VALUE_TEST});

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        boolean result = provider.isFlyoverDerivationHashUsed(btcTxHash, derivationHash);
        Assertions.assertTrue(result);
    }

    @Test
    void isFlyoverFederationDerivationHashUsed_beforeRSKIP176_returnFalse() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(false);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        boolean result = provider.isFlyoverDerivationHashUsed(btcTxHash, derivationHash);
        Assertions.assertFalse(result);
    }

    @Test
    void isFlyoverFederationDerivationHashUsed_storageReturnsNull_returnFalse() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash))
        ).thenReturn(null);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        boolean result = provider.isFlyoverDerivationHashUsed(btcTxHash, derivationHash);
        Assertions.assertFalse(result);
    }

    @Test
    void isFlyoverFederationDerivationHashUsed_storageReturnsEmpty_returnFalse() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash))
        ).thenReturn(new byte[]{});

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        boolean result = provider.isFlyoverDerivationHashUsed(btcTxHash, derivationHash);
        Assertions.assertFalse(result);
    }

    @Test
    void isFlyoverFederationDerivationHashUsed_storageReturnsWrongValue_returnFalse() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash))
        ).thenReturn(new byte[]{(byte) 0});

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        boolean result = provider.isFlyoverDerivationHashUsed(btcTxHash, derivationHash);
        Assertions.assertFalse(result);
    }

    @Test
    void saveNextFederationCreationBlockHeight_before_RSKIP186() {
        Repository repository1 = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository1);

        federationStorageProvider.setNextFederationCreationBlockHeight(10L);
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);

        // If the network upgrade is not enabled we shouldn't be saving to the repository
        verify(repository1, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey()),
                any()
        );

        Repository repository2 = mock(Repository.class);
        federationStorageProvider = createFederationStorageProvider(repository2);

        federationStorageProvider.clearNextFederationCreationBlockHeight();
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);

        // If the network upgrade is not enabled we shouldn't be saving to the repository
        verify(repository2, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(NEXT_FEDERATION_CREATION_BLOCK_HEIGHT_KEY.getKey()),
                any()
        );
    }

    @Test
    void getLastRetiredFederationP2SHScript_before_fork() {
        Repository repository = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.empty(), federationStorageProvider.getLastRetiredFederationP2SHScript(activationsBeforeFork));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, never()).getStorageBytes(bridgeAddress, LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey());
    }

    @Test
    void getLastRetiredFederationP2SHScript_after_fork() {
        Repository repository = mock(Repository.class);
        Script script = new Script(new byte[] {});
        // If by chance the repository is called I want to force the tests to fail
        when(repository.getStorageBytes(bridgeAddress, LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey()))
                .thenReturn(BridgeSerializationUtils.serializeScript(script));

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        assertEquals(Optional.of(script), federationStorageProvider.getLastRetiredFederationP2SHScript(activationsAllForks));

        // If the network upgrade is not enabled we shouldn't be reading the repository
        verify(repository, atLeastOnce()).getStorageBytes(bridgeAddress, LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey());
    }

    @Test
    void setLastRetiredFederationP2SHScriptAndGetLastRetiredFederationP2SHScript() {
        Repository repository = createRepository();
        Repository track = repository.startTracking();
        Script script = new Script(new byte[] {});

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(track);

        // We store the value
        federationStorageProvider.setLastRetiredFederationP2SHScript(script);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);
        track.commit();

        track = repository.startTracking();
        federationStorageProvider = createFederationStorageProvider(track);

        // And then we get it back
        MatcherAssert.assertThat(federationStorageProvider.getLastRetiredFederationP2SHScript(activationsAllForks), is(Optional.of(script)));
    }

    @Test
    void saveLastRetiredFederationP2SHScript_after_RSKIP186() {
        Repository repository = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        Script script = new Script(new byte[]{});

        federationStorageProvider.setLastRetiredFederationP2SHScript(script);
        federationStorageProvider.save(testnetBtcParams, activationsAllForks);

        // Once the network upgrade is active, we will store it in the repository
        verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey(),
                BridgeSerializationUtils.serializeScript(script)
        );
    }

    @Test
    void saveLastRetiredFederationP2SHScript_before_RSKIP186() {
        Repository repository = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        Script script = new Script(new byte[]{});

        federationStorageProvider.setLastRetiredFederationP2SHScript(script);
        federationStorageProvider.save(testnetBtcParams, activationsBeforeFork);

        // If the network upgrade is not enabled we shouldn't be saving to the repository
        verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(LAST_RETIRED_FEDERATION_P2SH_SCRIPT_KEY.getKey()),
                any()
        );
    }

    @Test
    void saveDerivationArgumentsScriptHash_afterRSKIP176_ok() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.markFlyoverDerivationHashAsUsed(btcTxHash, derivationHash);

        provider.save();

        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash),
            new byte[]{FAST_BRIDGE_FEDERATION_SCRIPT_HASH_TRUE_VALUE_TEST}
        );
        verifyNoMoreInteractions(repository);
    }

    @Test
    void saveDerivationArgumentsScriptHash_afterRSKIP176_nullBtcTxHash_notSaved() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.markFlyoverDerivationHashAsUsed(null, derivationHash);

        provider.save();

        verifyNoInteractions(repository);
    }

    @Test
    void saveDerivationArgumentsScriptHash_afterRSKIP176_nullDerivationHash_notSaved()
        throws IOException {
        Repository repository = mock(Repository.class);

        Sha256Hash btcTxHash = PegTestUtils.createHash(1);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.markFlyoverDerivationHashAsUsed(btcTxHash, null);

        provider.save();

        verifyNoInteractions(repository);
    }

    @Test
    void saveDerivationArgumentsScriptHash_beforeRSKIP176_ok() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        Sha256Hash btcTxHash = PegTestUtils.createHash(2);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(false);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.markFlyoverDerivationHashAsUsed(btcTxHash, derivationHash);

        provider.save();

        verify(repository, never()).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeHashUsedInBtcTx-" + btcTxHash + derivationHash),
            new byte[]{FAST_BRIDGE_FEDERATION_SCRIPT_HASH_TRUE_VALUE_TEST}
        );
    }

    @Test
    void getFlyoverFederationInformation_afterRSKIP176_ok() {
        Repository repository = mock(Repository.class);

        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte) 0xbb};
        Keccak256 derivationHash = PegTestUtils.createHash3(1);
        byte[] federationRedeemScriptHash = new byte[]{(byte) 0xaa};
        FlyoverFederationInformation flyoverFederationInformation = new FlyoverFederationInformation(
            derivationHash,
            federationRedeemScriptHash,
            flyoverFederationRedeemScriptHash
        );

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)))
        ).thenReturn(BridgeSerializationUtils.serializeFlyoverFederationInformation(flyoverFederationInformation));

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Optional <FlyoverFederationInformation> result = provider.getFlyoverFederationInformation(flyoverFederationRedeemScriptHash);

        Assertions.assertTrue((result.isPresent()));
        Assertions.assertArrayEquals(federationRedeemScriptHash, result.get().getFederationRedeemScriptHash());
        Assertions.assertArrayEquals(derivationHash.getBytes(), result.get().getDerivationHash().getBytes());
        Assertions.assertArrayEquals(flyoverFederationRedeemScriptHash, result.get().getFlyoverFederationRedeemScriptHash());
    }

    @Test
    void getFlyoverFederationInformation_beforeRSKIP176_ok() {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(2);
        byte[] federationRedeemScriptHash = new byte[]{(byte) 0xaa};
        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte)0x22};
        FlyoverFederationInformation flyoverFederationInformation = new FlyoverFederationInformation(
            derivationHash,
            federationRedeemScriptHash,
            flyoverFederationRedeemScriptHash
        );

        lenient().when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)))
        ).thenReturn(BridgeSerializationUtils.serializeFlyoverFederationInformation(flyoverFederationInformation));

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(false);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Optional<FlyoverFederationInformation> result = provider.getFlyoverFederationInformation(flyoverFederationRedeemScriptHash);
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void getFlyoverFederationInformation_notFound() {
        Repository repository = mock(Repository.class);

        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte) 0xaa};

        when(repository.getStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)))
        ).thenReturn(null);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Optional<FlyoverFederationInformation> result = provider.getFlyoverFederationInformation(flyoverFederationRedeemScriptHash);
        assertFalse(result.isPresent());
    }

    @Test
    void getFlyoverFederationInformation_nullParameter_returnEmpty() {
        Repository repository = mock(Repository.class);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Optional<FlyoverFederationInformation> result = provider.getFlyoverFederationInformation(null);
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void getFlyoverFederationInformation_arrayEmpty_returnEmpty() {
        Repository repository = mock(Repository.class);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        Optional<FlyoverFederationInformation> result = provider.getFlyoverFederationInformation(new byte[]{});
        Assertions.assertFalse(result.isPresent());
    }

    @Test
    void saveFlyoverFederationInformation_afterRSKIP176_ok() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(2);
        byte[] federationRedeemScriptHash = new byte[]{(byte) 0xaa};
        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte)0x22};
        FlyoverFederationInformation flyoverFederationInformation = new FlyoverFederationInformation(
            derivationHash,
            federationRedeemScriptHash,
            flyoverFederationRedeemScriptHash
        );

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.setFlyoverFederationInformation(flyoverFederationInformation);
        provider.save();

        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)),
            BridgeSerializationUtils.serializeFlyoverFederationInformation(flyoverFederationInformation)
        );
    }

    @Test
    void saveFlyoverFederationInformation_beforeRSKIP176_ok() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(2);
        byte[] federationRedeemScriptHash = new byte[]{(byte) 0xaa};
        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte)0x22};
        FlyoverFederationInformation flyoverFederationInformation = new FlyoverFederationInformation(
            derivationHash,
            federationRedeemScriptHash,
            flyoverFederationRedeemScriptHash
        );

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(false);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.setFlyoverFederationInformation(flyoverFederationInformation);
        provider.save();

        verify(repository, never()).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)),
            BridgeSerializationUtils.serializeFlyoverFederationInformation(flyoverFederationInformation)
        );
    }

    @Test
    void saveFlyoverFederationInformation_alreadySet_dont_set_again() throws IOException {
        Repository repository = mock(Repository.class);

        Keccak256 derivationHash = PegTestUtils.createHash3(2);
        byte[] federationRedeemScriptHash = new byte[]{(byte) 0xaa};
        byte[] flyoverFederationRedeemScriptHash = new byte[]{(byte)0x22};
        FlyoverFederationInformation flyoverFederationInformation = new FlyoverFederationInformation(
            derivationHash,
            federationRedeemScriptHash,
            flyoverFederationRedeemScriptHash
        );

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP176)).thenReturn(true);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository,
            bridgeAddress,
            testnetBtcParams,
            activations
        );

        provider.setFlyoverFederationInformation(flyoverFederationInformation);

        //Set again
        provider.setFlyoverFederationInformation(flyoverFederationInformation);
        provider.save();

        verify(repository, times(1)).addStorageBytes(
            bridgeAddress,
            DataWord.fromLongString("fastBridgeFederationInformation-" + Hex.toHexString(flyoverFederationRedeemScriptHash)),
            BridgeSerializationUtils.serializeFlyoverFederationInformation(flyoverFederationInformation)
        );
    }

    @Test
    void getReceiveHeadersLastTimestamp_before_RSKIP200() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsBeforeFork
        );

        assertFalse(provider.getReceiveHeadersLastTimestamp().isPresent());
    }

    @Test
    void getReceiveHeadersLastTimestamp_after_RSKIP200() {
        Repository repository = mock(Repository.class);

        long actualTimeStamp = System.currentTimeMillis();
        byte[] encodedTimeStamp = RLP.encodeBigInteger(BigInteger.valueOf(actualTimeStamp));
        when(repository.getStorageBytes(bridgeAddress, RECEIVE_HEADERS_TIMESTAMP.getKey()))
                .thenReturn(encodedTimeStamp);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        Optional<Long> result = provider.getReceiveHeadersLastTimestamp();

        assertTrue(result.isPresent());
        assertEquals(actualTimeStamp, (long) result.get());
    }

    @Test
    void getReceiveHeadersLastTimestamp_not_in_repository() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        assertFalse(provider.getReceiveHeadersLastTimestamp().isPresent());
    }

    @Test
    void saveReceiveHeadersLastTimestamp_before_RSKIP200() throws IOException {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsBeforeFork
        );

        provider.setReceiveHeadersLastTimestamp(System.currentTimeMillis());

        provider.save();
        verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(RECEIVE_HEADERS_TIMESTAMP.getKey()),
                any(byte[].class)
        );
    }

    @Test
    void saveReceiveHeadersLastTimestamp_after_RSKIP200() throws IOException {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        long timeInMillis = System.currentTimeMillis();
        provider.setReceiveHeadersLastTimestamp(timeInMillis);

        provider.save();
        verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                RECEIVE_HEADERS_TIMESTAMP.getKey(),
                BridgeSerializationUtils.serializeLong(timeInMillis)
        );
    }

    @Test
    void saveReceiveHeadersLastTimestamp_not_set() throws IOException {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        provider.save();
        verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(RECEIVE_HEADERS_TIMESTAMP.getKey()),
                any(byte[].class)
        );
    }

    @Test
    void getNextPegoutHeight_before_RSKIP271_activation() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsBeforeFork
        );

        assertEquals(Optional.empty(), provider.getNextPegoutHeight());

        verify(repository, never()).getStorageBytes(bridgeAddress, NEXT_PEGOUT_HEIGHT_KEY.getKey());
    }

    @Test
    void getNextPegoutHeight_after_RSKIP271_activation() {
        Repository repository = mock(Repository.class);

        when(repository.getStorageBytes(bridgeAddress, NEXT_PEGOUT_HEIGHT_KEY.getKey())).thenReturn(new byte[] { 1 });

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        assertEquals(Optional.of(1L), provider.getNextPegoutHeight());

        verify(repository, atLeastOnce()).getStorageBytes(bridgeAddress, NEXT_PEGOUT_HEIGHT_KEY.getKey());
    }

    @Test
    void setNextPegoutHeightAndGetNextPegoutHeight_after_RSKIP271_activation() {
        Repository repository = createRepository();
        Repository track = repository.startTracking();

        BridgeStorageProvider provider1 = new BridgeStorageProvider(
            track, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        provider1.setNextPegoutHeight(1L);
        provider1.saveNextPegoutHeight();
        track.commit();

        track = repository.startTracking();

        BridgeStorageProvider provider2 = new BridgeStorageProvider(
            track, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        MatcherAssert.assertThat(provider2.getNextPegoutHeight(), is(Optional.of(1L)));
    }

    @Test
    void saveNextPegoutHeight_before_RSKIP271() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsBeforeFork
        );

        provider.setNextPegoutHeight(10L);
        provider.saveNextPegoutHeight();

        verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(NEXT_PEGOUT_HEIGHT_KEY.getKey()),
                any()
        );
    }

    @Test
    void saveNextPegoutHeight_after_RSKIP271() {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider provider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        provider.setNextPegoutHeight(10L);
        provider.saveNextPegoutHeight();

        verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                NEXT_PEGOUT_HEIGHT_KEY.getKey(),
                BridgeSerializationUtils.serializeLong(10L)
        );
    }

    @Test
    void getNewFederationBtcUTXOs_before_RSKIP284_before_RSKIP293_testnet() throws IOException {
        testGetNewFederationBtcUTXOs(false, false, NetworkParameters.ID_TESTNET);
    }

    @Test
    void getNewFederationBtcUTXOs_before_RSKIP284_before_RSKIP293_mainnet() throws IOException {
        testGetNewFederationBtcUTXOs(false, false, NetworkParameters.ID_MAINNET);
    }

    @Test
    void getNewFederationBtcUTXOs_before_RSKIP284_after_RSKIP293_testnet() throws IOException {
        testGetNewFederationBtcUTXOs(false, true, NetworkParameters.ID_TESTNET);
    }

    @Test
    void getNewFederationBtcUTXOs_before_RSKIP284_after_RSKIP293_mainnet() throws IOException {
        testGetNewFederationBtcUTXOs(false, true, NetworkParameters.ID_MAINNET);
    }

    @Test
    void getNewFederationBtcUTXOs_after_RSKIP284_before_RSKIP293_testnet() throws IOException {
        testGetNewFederationBtcUTXOs(true, false, NetworkParameters.ID_TESTNET);
    }

    @Test
    void getNewFederationBtcUTXOs_after_RSKIP284_before_RSKIP293_mainnet() throws IOException {
        testGetNewFederationBtcUTXOs(true, false, NetworkParameters.ID_MAINNET);
    }

    @Test
    void getNewFederationBtcUTXOs_after_RSKIP284_after_RSKIP293_testnet() throws IOException {
        testGetNewFederationBtcUTXOs(true, true, NetworkParameters.ID_TESTNET);
    }

    @Test
    void getNewFederationBtcUTXOs_after_RSKIP284_after_RSKIP293_mainnet() throws IOException {
        testGetNewFederationBtcUTXOs(true, true, NetworkParameters.ID_MAINNET);
    }

    @Test
    void saveNewFederationBtcUTXOs_no_data() throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);

        Repository repository = mock(Repository.class);

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        federationStorageProvider.save(testnetBtcParams, activations);

        verify(repository, times(0)).addStorageBytes(
            eq(bridgeAddress),
            eq(NEW_FEDERATION_BTC_UTXOS_KEY.getKey()),
            any()
        );
    }

    @Test
    void saveNewFederationBtcUTXOs_before_RSKIP284_activation_testnet() throws IOException {
        testSaveNewFederationBtcUTXOs(false, NetworkParameters.ID_TESTNET);
    }

    @Test
    void saveNewFederationBtcUTXOs_after_RSKIP284_activation_testnet() throws IOException {
        testSaveNewFederationBtcUTXOs(true, NetworkParameters.ID_TESTNET);
    }

    @Test
    void saveNewFederationBtcUTXOs_before_RSKIP284_activation_mainnet() throws IOException {
        testSaveNewFederationBtcUTXOs(false, NetworkParameters.ID_MAINNET);
    }

    @Test
    void saveNewFederationBtcUTXOs_after_RSKIP284_activation_mainnet() throws IOException {
        testSaveNewFederationBtcUTXOs(true, NetworkParameters.ID_MAINNET);
    }

    @Test
    void getReleaseRequestQueueSize_when_releaseRequestQueue_is_null() throws IOException {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        Assertions.assertEquals(0, storageProvider.getReleaseRequestQueueSize());
    }

    @Test
    void getReleaseRequestQueueSize_when_releaseRequestQueue_is_not_null() throws IOException {
        Repository repository = mock(Repository.class);

        BridgeStorageProvider storageProvider = new BridgeStorageProvider(
            repository, bridgeAddress,
            testnetBtcParams, activationsAllForks
        );

        ReleaseRequestQueue releaseRequestQueue = storageProvider.getReleaseRequestQueue();

        releaseRequestQueue.add(Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mseEsMLuzaEdGbyAv9c9VRL9qGcb49qnxB"),
            Coin.COIN,
            PegTestUtils.createHash3(0));

        releaseRequestQueue.add(Address.fromBase58((new BridgeRegTestConstants()).getBtcParams(), "mmWJhA74Pd6peL39V3AmtGHdGdJ4PyeXvL"),
            Coin.COIN,
            PegTestUtils.createHash3(1));

        Assertions.assertEquals(2, storageProvider.getReleaseRequestQueueSize());
    }

    private void testGetOldFederation(Federation oldFederation, ActivationConfig.ForBlock activations) {
        List<Integer> storageCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(any(RskAddress.class), any(DataWord.class))).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                int federationVersion = oldFederation.getFormatVersion();
                return RLP.encodeBigInteger(BigInteger.valueOf(federationVersion));
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                return BridgeSerializationUtils.serializeFederation(oldFederation);
            }
        });

            assertEquals(oldFederation, federationStorageProvider.getOldFederation(federationTestnetConstants, activations));
            assertEquals(2, storageCalls.size());
    }

    private void testSaveOldFederation(Federation oldFederation, int version, ActivationConfig.ForBlock activations) {
        List<Integer> storageBytesCalls = new ArrayList<>();
        List<Integer> serializeCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            useOriginalIntegerSerialization(bridgeSerializationUtilsMocked);

            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeFederation(any(Federation.class))).then((InvocationOnMock invocation) -> {
                Federation federation = invocation.getArgument(0);
                Assertions.assertEquals(oldFederation, federation);
                serializeCalls.add(0);
                return new byte[]{(byte) 0xbb};
            });

            doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);

                if (storageBytesCalls.size() == 1) {
                    // First call is the version setting
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(OLD_FEDERATION_FORMAT_VERSION.getKey(), address);
                    Assertions.assertEquals(BigInteger.valueOf(version), RLP.decodeBigInteger(data, 0));
                } else {
                    Assertions.assertEquals(2, storageBytesCalls.size());
                    // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(OLD_FEDERATION_KEY.getKey(), address);
                    assertArrayEquals(new byte[]{(byte) 0xbb}, data);
                }
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

            federationStorageProvider.save(testnetBtcParams, activations);
            // Shouldn't have tried to save nor serialize anything
            assertEquals(0, storageBytesCalls.size());
            assertEquals(0, serializeCalls.size());
            federationStorageProvider.setOldFederation(oldFederation);
            federationStorageProvider.save(testnetBtcParams, activations);
            assertEquals(2, storageBytesCalls.size());
            assertEquals(1, serializeCalls.size());
        }
    }

    private void testGetNewFederationPostMultiKey(Federation federation) {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        List<Integer> storageCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        when(repositoryMock.getStorageBytes(
            any(RskAddress.class),
            any(DataWord.class)
        )).then((InvocationOnMock invocation) -> {
            storageCalls.add(0);
            RskAddress contractAddress = invocation.getArgument(0);
            DataWord address = invocation.getArgument(1);

            assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());

            if (storageCalls.size() == 1) {
                // First call is storage version getter
                Assertions.assertEquals(NEW_FEDERATION_FORMAT_VERSION.getKey(), address);
                int federationVersion = federation.getFormatVersion();
                return RLP.encodeBigInteger(BigInteger.valueOf(federationVersion));
            } else {
                // Second call is the actual storage getter
                Assertions.assertEquals(2, storageCalls.size());
                Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                return BridgeSerializationUtils.serializeFederation(federation);
            }
        });

            assertEquals(federation, federationStorageProvider.getNewFederation(federationTestnetConstants, activations));
            assertEquals(2, storageCalls.size());
    }

    private void testSaveNewFederationPostMultiKey(Federation newFederation, int version, ActivationConfig.ForBlock activations) {
        List<Integer> storageBytesCalls = new ArrayList<>();
        List<Integer> serializeCalls = new ArrayList<>();
        Repository repositoryMock = mock(Repository.class);
        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repositoryMock);

        try (MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked = mockStatic(BridgeSerializationUtils.class)) {
            useOriginalIntegerSerialization(bridgeSerializationUtilsMocked);

            bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeFederation(any(Federation.class))).then((InvocationOnMock invocation) -> {
                Federation federation = invocation.getArgument(0);
                Assertions.assertEquals(newFederation, federation);
                serializeCalls.add(0);
                return new byte[]{(byte) 0xbb};
            });

            Mockito.doAnswer((InvocationOnMock invocation) -> {
                storageBytesCalls.add(0);
                RskAddress contractAddress = invocation.getArgument(0);
                DataWord address = invocation.getArgument(1);
                byte[] data = invocation.getArgument(2);

                if (storageBytesCalls.size() == 1) {
                    // First call is the version setting
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(NEW_FEDERATION_FORMAT_VERSION.getKey(), address);
                    Assertions.assertEquals(BigInteger.valueOf(version), RLP.decodeBigInteger(data, 0));
                } else {
                    Assertions.assertEquals(2, storageBytesCalls.size());
                    // Make sure the bytes are set to the correct address in the repo and that what's saved is what was serialized
                    assertArrayEquals(Hex.decode("aabbccdd"), contractAddress.getBytes());
                    Assertions.assertEquals(NEW_FEDERATION_KEY.getKey(), address);
                    assertArrayEquals(new byte[]{(byte) 0xbb}, data);
                }
                return null;
            }).when(repositoryMock).addStorageBytes(any(RskAddress.class), any(DataWord.class), any(byte[].class));

            federationStorageProvider.save(testnetBtcParams, activations);
            // Shouldn't have tried to save nor serialize anything
            Assertions.assertEquals(0, storageBytesCalls.size());
            Assertions.assertEquals(0, serializeCalls.size());
            federationStorageProvider.setNewFederation(newFederation);
            federationStorageProvider.save(testnetBtcParams, activations);
            Assertions.assertEquals(2, storageBytesCalls.size());
            Assertions.assertEquals(1, serializeCalls.size());
        }
    }

    private void testGetNewFederationBtcUTXOs(boolean isRskip284Active, boolean isRskip293Active, String networkId) throws IOException {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(isRskip284Active);
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(isRskip293Active);

        NetworkParameters networkParameters = NetworkParameters.fromID(networkId);

        Repository repository = mock(Repository.class);
        List<UTXO> federationUtxos = Arrays.asList(
            PegTestUtils.createUTXO(1, 0, Coin.COIN),
            PegTestUtils.createUTXO(2, 2, Coin.COIN.divide(2)),
            PegTestUtils.createUTXO(3, 0, Coin.COIN.multiply(3))
        );
        when(repository.getStorageBytes(
            bridgeAddress,
            NEW_FEDERATION_BTC_UTXOS_KEY.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeUTXOList(federationUtxos));

        List<UTXO> federationUtxosAfterRskip284Activation = Arrays.asList(
            PegTestUtils.createUTXO(4, 0, Coin.FIFTY_COINS),
            PegTestUtils.createUTXO(5, 2, Coin.COIN.multiply(2))
        );
        when(repository.getStorageBytes(
            bridgeAddress,
            NEW_FEDERATION_BTC_UTXOS_KEY_FOR_TESTNET_PRE_HOP.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeUTXOList(federationUtxosAfterRskip284Activation));

        List<UTXO> federationUtxosAfterRskip293Activation = Arrays.asList(
            PegTestUtils.createUTXO(6, 1, Coin.valueOf(150_000)),
            PegTestUtils.createUTXO(7, 3, Coin.COIN.multiply(3))
        );
        when(repository.getStorageBytes(
            bridgeAddress,
            NEW_FEDERATION_BTC_UTXOS_KEY_FOR_TESTNET_POST_HOP.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeUTXOList(federationUtxosAfterRskip293Activation));

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        List<UTXO> obtainedUtxos = federationStorageProvider.getNewFederationBtcUTXOs(networkParameters, activations);

        if (!networkId.equals(NetworkParameters.ID_TESTNET)) {
            Assertions.assertEquals(federationUtxos, obtainedUtxos);
            return;
        }

        // testnet
        // rskip284 & rskip293 are not active
        if (!isRskip284Active) {
            Assertions.assertEquals(federationUtxos, obtainedUtxos);
            return;
        }

        // rskip284 is active
        if (!isRskip293Active) {
            Assertions.assertEquals(federationUtxosAfterRskip284Activation, obtainedUtxos);
            return;
        }

        // rskip293 is active
        Assertions.assertEquals(federationUtxosAfterRskip293Activation, obtainedUtxos);
    }

    private void testSaveNewFederationBtcUTXOs(boolean isRskip284Active, String networkId) throws IOException {
        NetworkParameters networkParameters = NetworkParameters.fromID(networkId);

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(isRskip284Active);

        Repository repository = mock(Repository.class);
        List<UTXO> federationUtxos = Arrays.asList(
            PegTestUtils.createUTXO(1, 0, Coin.COIN),
            PegTestUtils.createUTXO(2, 2, Coin.COIN.divide(2)),
            PegTestUtils.createUTXO(3, 0, Coin.COIN.multiply(3))
        );
        when(repository.getStorageBytes(
            bridgeAddress,
            NEW_FEDERATION_BTC_UTXOS_KEY.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeUTXOList(federationUtxos));

        List<UTXO> federationUtxosAfterRskipActivation = Arrays.asList(
            PegTestUtils.createUTXO(4, 0, Coin.FIFTY_COINS),
            PegTestUtils.createUTXO(5, 2, Coin.COIN.multiply(2))
        );
        when(repository.getStorageBytes(
            bridgeAddress,
            NEW_FEDERATION_BTC_UTXOS_KEY_FOR_TESTNET_PRE_HOP.getKey()
        )).thenReturn(BridgeSerializationUtils.serializeUTXOList(federationUtxosAfterRskipActivation));

        FederationStorageProvider federationStorageProvider = createFederationStorageProvider(repository);

        federationStorageProvider.getNewFederationBtcUTXOs(networkParameters, activations); // Ensure there are elements in the UTXOs list
        federationStorageProvider.save(networkParameters, activations);

        if (isRskip284Active && networkId.equals(NetworkParameters.ID_TESTNET)) {
            verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(NEW_FEDERATION_BTC_UTXOS_KEY.getKey()),
                any()
            );
            verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                NEW_FEDERATION_BTC_UTXOS_KEY_FOR_TESTNET_PRE_HOP.getKey(),
                BridgeSerializationUtils.serializeUTXOList(federationUtxosAfterRskipActivation)
            );
        } else {
            verify(repository, times(1)).addStorageBytes(
                bridgeAddress,
                NEW_FEDERATION_BTC_UTXOS_KEY.getKey(),
                BridgeSerializationUtils.serializeUTXOList(federationUtxos)
            );
            verify(repository, never()).addStorageBytes(
                eq(bridgeAddress),
                eq(NEW_FEDERATION_BTC_UTXOS_KEY_FOR_TESTNET_PRE_HOP.getKey()),
                any()
            );
        }
    }

    private BtcTransaction createTransaction() {
        BtcTransaction tx = new BtcTransaction(testnetBtcParams);
        tx.addInput(PegTestUtils.createHash(), transactionOffset++, ScriptBuilder.createInputScript(new TransactionSignature(BigInteger.ONE, BigInteger.TEN)));

        return tx;
    }

    private Federation buildMockFederation(Integer... pks) {
        FederationArgs federationArgs = new FederationArgs(FederationTestUtils.getFederationMembersFromPks(pks),
            Instant.ofEpochMilli(1000),
            1, testnetBtcParams);
        return FederationFactory.buildStandardMultiSigFederation(federationArgs);
    }

    private PendingFederation buildMockPendingFederation(Integer... pks) {
        return new PendingFederation(FederationTestUtils.getFederationMembersFromPks(pks));
    }

    private void useOriginalIntegerSerialization(MockedStatic<BridgeSerializationUtils> bridgeSerializationUtilsMocked) {
        bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.serializeInteger(any(Integer.class))).thenCallRealMethod();
        bridgeSerializationUtilsMocked.when(() -> BridgeSerializationUtils.deserializeInteger(any(byte[].class))).thenCallRealMethod();
    }

    private static Repository createRepository() {
        TrieStore trieStore = new TrieStoreImpl(new HashMapDB());
        return new MutableRepository(new MutableTrieCache(new MutableTrieImpl(trieStore, new Trie(trieStore))));
    }

    private FederationStorageProvider createFederationStorageProvider(Repository repository) {
        StorageAccessor bridgeStorageAccessor = new BridgeStorageAccessorImpl(repository);
        return new FederationStorageProviderImpl(bridgeStorageAccessor);
    }

    private BridgeStorageProvider createBridgeStorageProvider(Repository repository, NetworkParameters networkParameters, ActivationConfig.ForBlock activations) {
        return new BridgeStorageProvider(repository, bridgeAddress, networkParameters, activations);
    }
}
