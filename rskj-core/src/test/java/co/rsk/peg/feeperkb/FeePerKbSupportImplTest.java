package co.rsk.peg.feeperkb;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Coin;
import co.rsk.core.RskAddress;
import co.rsk.peg.BridgeSerializationUtils;
import co.rsk.peg.feeperkb.constants.FeePerKbConstants;
import co.rsk.peg.feeperkb.constants.FeePerKbMainNetConstants;
import co.rsk.peg.vote.ABICallElection;
import co.rsk.peg.vote.ABICallSpec;
import co.rsk.peg.vote.AddressBasedAuthorizer;
import java.util.Optional;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.core.SignatureCache;
import org.ethereum.core.Transaction;
import org.ethereum.crypto.ECKey;
import org.ethereum.util.ByteUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeePerKbSupportImplTest {

    @Mock
    FeePerKbStorageProvider provider;
    FeePerKbConstants feePerKbConstants;
    @InjectMocks
    FeePerKbSupportImpl feePerKbSupport;
    private static final ECKey feePerKbAuthorizedKey = ECKey.fromPublicOnly(Hex.decode("0448f51638348b034995b1fd934fe14c92afde783e69f120a46ee16eb6bdc2e4f6b5e37772094c68c0dea2b1be3d96ea9651a9eebda7304914c8047f4e3e251378"));


    @BeforeEach
    void setUp() {
        feePerKbConstants = FeePerKbMainNetConstants.getInstance();
        feePerKbSupport = new FeePerKbSupportImpl(feePerKbConstants, provider);
    }

    @Test
    void getFeePerKb() {
        Optional<Coin> currentFeePerKb = Optional.of(Coin.valueOf(50_000L));
        when(provider.getFeePerKb()).thenReturn(currentFeePerKb);

        Coin actualResult = feePerKbSupport.getFeePerKb();

        Coin expectedResult = currentFeePerKb.get();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void getFeePerKb_nullInStorageProvider_shouldReturnGenesisFeePerKb() {
        Coin actualResult = feePerKbSupport.getFeePerKb();

        Coin expectedResult = feePerKbConstants.getGenesisFeePerKb();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_withUnauthorizedSignature_shouldReturnUnauthorizedCallerResponseCode() {
        byte[] unauthorizedKey = ByteUtil.leftPadBytes(new byte[]{0x43}, 20);
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(unauthorizedKey);
        when(tx.getSender(signatureCache)).thenReturn(sender);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, Coin.valueOf(50_000L), signatureCache);

        Integer expectedResult = FeePerKbResponseCode.UNAUTHORIZED_CALLER.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_negativeFeePerKbValue_shouldReturnNegativeFeeVotedResponseCode() {
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(feePerKbAuthorizedKey.getAddress());
        when(tx.getSender(signatureCache)).thenReturn(sender);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, Coin.NEGATIVE_SATOSHI, signatureCache);

        Integer expectedResult = FeePerKbResponseCode.NEGATIVE_FEE_VOTED.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_excessiveFeePerKbValue_shouldReturnExcessiveFeeVotedResponseCode() {
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(feePerKbAuthorizedKey.getAddress());
        when(tx.getSender(signatureCache)).thenReturn(sender);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, Coin.COIN, signatureCache);

        Integer expectedResult = FeePerKbResponseCode.EXCESSIVE_FEE_VOTED.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_unsuccessfulVote_shouldReturnUnsuccessfulFeeVotedResponseCode() {
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(feePerKbAuthorizedKey.getAddress());
        when(tx.getSender(signatureCache)).thenReturn(sender);
        ABICallElection feePerKbElection = mock(ABICallElection.class);
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        when(provider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, Coin.valueOf(50_000L), signatureCache);

        Integer expectedResult = FeePerKbResponseCode.UNSUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_successfulFeePerKbVote_shouldReturnSuccessfulFeeVotedResponseCode() {
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(feePerKbAuthorizedKey.getAddress());
        when(tx.getSender(signatureCache)).thenReturn(sender);
        ABICallElection feePerKbElection = mock(ABICallElection.class);
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        when(provider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);
        when(feePerKbElection.vote(any(), any())).thenReturn(true);

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, Coin.valueOf(50_000L), signatureCache);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_successfulFeePerKbChanged_shouldReturnSuccessfulFeeVotedResponseCode() {
        final String SET_FEE_PER_KB_ABI_FUNCTION = "setFeePerKb";
        final Coin feePerKb = Coin.valueOf(50_000L);
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);
        RskAddress sender = new RskAddress(feePerKbAuthorizedKey.getAddress());
        when(tx.getSender(signatureCache)).thenReturn(sender);
        ABICallElection feePerKbElection = mock(ABICallElection.class);
        AddressBasedAuthorizer authorizer = feePerKbConstants.getFeePerKbChangeAuthorizer();
        when(provider.getFeePerKbElection(authorizer)).thenReturn(feePerKbElection);
        when(feePerKbElection.vote(any(), any())).thenReturn(true);
        ABICallSpec feeVote = new ABICallSpec(SET_FEE_PER_KB_ABI_FUNCTION, new byte[][]{BridgeSerializationUtils.serializeCoin(feePerKb)});
        when(feePerKbElection.getWinner()).thenReturn(Optional.of(feeVote));

        Integer actualResult = feePerKbSupport.voteFeePerKbChange(tx, feePerKb, signatureCache);

        Integer expectedResult = FeePerKbResponseCode.SUCCESSFUL_VOTE.getCode();
        assertEquals(expectedResult, actualResult);
    }

    @Test
    void voteFeePerKbChange_nullFeeThrows() {
        Transaction tx = mock(Transaction.class);
        SignatureCache signatureCache = mock(SignatureCache.class);

        assertThrows(NullPointerException.class,
            () -> feePerKbSupport.voteFeePerKbChange(tx, null, signatureCache));
        verify(provider, never()).setFeePerKb(any());
    }

    @Test
    void save() {
        doNothing().when(provider).save();

        feePerKbSupport.save();

        verify(provider, times(1)).save();
    }
}
