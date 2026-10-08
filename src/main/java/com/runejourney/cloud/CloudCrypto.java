package com.runejourney.cloud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Everything uploaded is encrypted with the player's data key first, so the storage only ever holds
 * ciphertext. Each file is AES-256-GCM with a fresh nonce, and is bound to where it belongs (the
 * account, document and PC): a file moved anywhere else, or changed at all, fails to open.
 * <pre>
 *   "RJE" | format version (1 byte) | key ID (4 bytes) | nonce (12 bytes) | ciphertext and tag
 * </pre>
 */
final class CloudCrypto
{
	private static final byte[] MAGIC = {'R', 'J', 'E'};
	private static final int FORMAT = 1;
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int HEADER_BYTES = MAGIC.length + 1 + 4 + NONCE_BYTES;
	private static final SecureRandom RANDOM = new SecureRandom();

	private CloudCrypto()
	{
	}

	static byte[] decodeKey(String base64) throws GeneralSecurityException
	{
		byte[] key;
		try
		{
			key = Base64.getDecoder().decode(base64);
		}
		catch (IllegalArgumentException e)
		{
			throw new GeneralSecurityException("Data key isn't valid base64");
		}
		if (key.length != 32)
		{
			throw new GeneralSecurityException("Data key should be 256 bits");
		}
		return key;
	}

	/**
	 * @param binding where the file belongs, see {@link #binding}
	 */
	static byte[] seal(byte[] key, int keyId, byte[] plain, String binding) throws GeneralSecurityException
	{
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
		header.put(MAGIC).put((byte) FORMAT).putInt(keyId).put(nonce);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(header.array());
		cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
		byte[] sealed = cipher.doFinal(plain);

		byte[] out = new byte[HEADER_BYTES + sealed.length];
		System.arraycopy(header.array(), 0, out, 0, HEADER_BYTES);
		System.arraycopy(sealed, 0, out, HEADER_BYTES, sealed.length);
		return out;
	}

	static byte[] open(byte[] key, int keyId, byte[] file, String binding) throws GeneralSecurityException
	{
		if (file.length < HEADER_BYTES || !Arrays.equals(Arrays.copyOf(file, MAGIC.length), MAGIC) || file[MAGIC.length] != FORMAT)
		{
			throw new GeneralSecurityException("Not a RuneJourney cloud file");
		}
		ByteBuffer header = ByteBuffer.wrap(file, 0, HEADER_BYTES);
		header.position(MAGIC.length + 1);
		int fileKey = header.getInt();
		if (fileKey != keyId)
		{
			throw new GeneralSecurityException("Encrypted with a different key (" + fileKey + ")");
		}
		byte[] nonce = Arrays.copyOfRange(file, HEADER_BYTES - NONCE_BYTES, HEADER_BYTES);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
		cipher.updateAAD(file, 0, HEADER_BYTES);
		cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
		return cipher.doFinal(file, HEADER_BYTES, file.length - HEADER_BYTES);
	}

	/**
	 * Where a file belongs. Journey documents belong to the PC that wrote them; screenshots belong
	 * to the account.
	 */
	static String binding(String userUuid, String profileId, String kind, String docKey, String deviceId)
	{
		return "runejourney|" + userUuid + "|" + profileId + "|" + kind + "|" + docKey + (deviceId != null ? "|" + deviceId : "");
	}

	static byte[] gzip(byte[] plain) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 4));
		try (GZIPOutputStream gz = new GZIPOutputStream(out))
		{
			gz.write(plain);
		}
		return out.toByteArray();
	}

	/**
	 * @param max the most it may expand to, so a hostile file can't fill the memory
	 */
	static byte[] gunzip(byte[] compressed, int max) throws IOException
	{
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed)))
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
			{
				if (out.size() + n > max)
				{
					throw new IOException("Document is too large");
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	static String sha256(byte[] data)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash)
			{
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static String sha256(String text)
	{
		return sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	static String randomHex(int bytes)
	{
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		StringBuilder hex = new StringBuilder(bytes * 2);
		for (byte x : b)
		{
			hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
		}
		return hex.toString();
	}
}
