package com.runejourney.cloud;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import org.junit.Test;

public class CloudCryptoTest
{
	private static final String HERE = CloudCrypto.binding("user", "p1", "journey", "day:2026-10-02", "dev1");

	private static byte[] key()
	{
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	@Test
	public void roundTrips() throws Exception
	{
		byte[] key = key();
		byte[] plain = CloudCrypto.gzip("{\"playMillis\":600}".getBytes(StandardCharsets.UTF_8));
		byte[] sealed = CloudCrypto.seal(key, 1, plain, HERE);
		assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("playMillis"));
		assertArrayEquals(plain, CloudCrypto.open(key, 1, sealed, HERE));
		assertArrayEquals("{\"playMillis\":600}".getBytes(StandardCharsets.UTF_8), CloudCrypto.gunzip(plain, 1024));
	}

	/**
	 * A file moved to another document, PC or account can't be opened there.
	 */
	@Test
	public void aMovedFileFailsToOpen() throws Exception
	{
		byte[] key = key();
		byte[] sealed = CloudCrypto.seal(key, 1, new byte[]{1, 2, 3}, HERE);
		expectFailure(() -> CloudCrypto.open(key, 1, sealed, CloudCrypto.binding("user", "p1", "journey", "day:2026-10-03", "dev1")));
		expectFailure(() -> CloudCrypto.open(key, 1, sealed, CloudCrypto.binding("user", "p1", "journey", "day:2026-10-02", "dev2")));
		expectFailure(() -> CloudCrypto.open(key, 1, sealed, CloudCrypto.binding("other", "p1", "journey", "day:2026-10-02", "dev1")));
	}

	@Test
	public void aChangedFileOrWrongKeyFailsToOpen() throws Exception
	{
		byte[] key = key();
		byte[] sealed = CloudCrypto.seal(key, 1, new byte[]{1, 2, 3}, HERE);
		byte[] changed = sealed.clone();
		changed[changed.length - 1] ^= 1;
		expectFailure(() -> CloudCrypto.open(key, 1, changed, HERE));
		expectFailure(() -> CloudCrypto.open(key(), 1, sealed, HERE));
		expectFailure(() -> CloudCrypto.open(key, 2, sealed, HERE));
	}

	@Test
	public void aHugeDocumentIsRefused() throws Exception
	{
		byte[] zeros = CloudCrypto.gzip(new byte[1_000_000]);
		try
		{
			CloudCrypto.gunzip(zeros, 10_000);
			fail();
		}
		catch (java.io.IOException expected)
		{
			// Expected
		}
	}

	private interface Open
	{
		void run() throws GeneralSecurityException;
	}

	private static void expectFailure(Open open)
	{
		try
		{
			open.run();
			fail("Should not open");
		}
		catch (GeneralSecurityException expected)
		{
			// Expected
		}
	}
}
