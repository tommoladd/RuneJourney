package com.runejourney.cloud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import net.runelite.api.JagexColor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CharacterModelTest
{
	@Test
	public void colorsMatchTheGame()
	{
		// The game's own conversion back finds the same colour
		for (int hsl : new int[]{960, 6_464, 21_570, 43_072, 54_212, 30_000})
		{
			short back = JagexColor.rgbToHSL(CharacterModel.rgb(hsl), 0.7);
			assertEquals("hue of " + hsl, JagexColor.unpackHue((short) hsl), JagexColor.unpackHue(back), 1);
			assertEquals("saturation of " + hsl, JagexColor.unpackSaturation((short) hsl), JagexColor.unpackSaturation(back), 1);
			assertEquals("lightness of " + hsl, JagexColor.unpackLuminance((short) hsl), JagexColor.unpackLuminance(back), 1);
		}
		// Too dark or light to have a hue
		assertEquals(0, CharacterModel.rgb(0));
		assertTrue((CharacterModel.rgb(127) & 0xFF) > 250);
	}

	@Test
	public void encodesVisibleFaces()
	{
		// A flat face, a hidden one, a see-through textured one and an invisible one
		CharacterModel model = CharacterModel.of(4,
			new float[]{0, 64.4f, 0, 32}, new float[]{0, 0, -200, -100}, new float[]{0, 0, 0, 10},
			4, new int[]{0, 0, 1, 0}, new int[]{1, 1, 2, 1}, new int[]{2, 2, 3, 3},
			new int[]{960, 960, 100, 960}, new int[]{0, 960, 50, 960}, new int[]{-1, -2, 120, 0},
			new byte[]{0, 0, (byte) 128, (byte) 255}, new int[]{-1, -1, 21_570, -1});
		ByteBuffer in = ByteBuffer.wrap(gunzip(model.encode())).order(ByteOrder.LITTLE_ENDIAN);

		byte[] magic = new byte[4];
		in.get(magic);
		assertEquals("RJM1", new String(magic, StandardCharsets.US_ASCII));
		assertEquals(4, in.getShort());
		assertEquals(2, in.getShort());
		assertEquals(8 + 4 * 6 + 2 * 16, in.capacity());
		short[] vertices = new short[12];
		in.asShortBuffer().get(vertices);
		in.position(in.position() + 24);
		assertEquals(64, vertices[3]);
		assertEquals(-200, vertices[7]);
		assertEquals(10, vertices[11]);

		assertEquals(0, in.getShort());
		assertEquals(1, in.getShort());
		assertEquals(2, in.getShort());
		assertEquals(1, in.getShort());
		assertEquals(2, in.getShort());
		assertEquals(3, in.getShort());

		byte[] flat = new byte[9];
		in.get(flat);
		int red = CharacterModel.rgb(960);
		for (int corner = 0; corner < 3; corner++)
		{
			assertEquals((byte) (red >> 16), flat[corner * 3]);
			assertEquals((byte) (red >> 8), flat[corner * 3 + 1]);
			assertEquals((byte) red, flat[corner * 3 + 2]);
		}
		byte[] textured = new byte[9];
		in.get(textured);
		assertTrue("Lit more brightly at its third corner", (textured[8] & 0xFF) > (textured[5] & 0xFF));

		assertEquals(255, in.get() & 0xFF);
		assertEquals(127, in.get() & 0xFF);
		assertEquals(0, in.remaining());
	}

	@Test
	public void aModelWithNothingVisibleIsNotKept()
	{
		assertNull(CharacterModel.of(3, new float[3], new float[3], new float[3],
			1, new int[]{0}, new int[]{1}, new int[]{2}, new int[]{960}, new int[]{960}, new int[]{-2}, null, null));
	}

	static byte[] gunzip(byte[] gzipped)
	{
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped)))
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; )
			{
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
