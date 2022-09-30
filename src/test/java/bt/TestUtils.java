package bt;

import signumj.crypto.SignumCrypto;
import signumj.entity.SignumValue;
import signumj.entity.response.AT;

import static org.junit.Assert.assertNotNull;

public class TestUtils extends BT {

	public static <T extends Contract> AT registerContract(Class<T> contractClass) {
		AT at = null;
		try {
			at = registerContract(contractClass, SignumValue.fromSigna(10));
		} catch (Exception e) {
			throw new RuntimeException("Error registering contract", e);
		}
		assertNotNull("AT could not be registered", at);
		return at;
	}

	public static double getBalance(String passPhrase) {
		return bns.getAccount(SignumCrypto.getInstance().getAddressFromPassphrase(passPhrase)).blockingGet().getUnconfirmedBalance().doubleValue();
	}

}
