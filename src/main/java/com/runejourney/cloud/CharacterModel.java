package com.runejourney.cloud;

import java.io.*;
import java.nio.*;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import lombok.*;
import net.runelite.api.*;

@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class CharacterModel
{
	static final int MAX_VERTICES = 20_000;
	static final int MAX_FACES = 20_000;
	private static final double BRIGHTNESS = 0.7;

	@Getter
	private final short[] vertices;
	@Getter
	private final int[] faces;
	@Getter
	private final byte[] colors;
	@Getter
	private final byte[] alphas;

	public static String look(PlayerComposition c)
	{
		return CloudCrypto.sha256(c.getGender() + "|" + Arrays.toString(c.getEquipmentIds()) + "|" + Arrays.toString(c.getColors()));
	}

	public static CharacterModel capture(Model model, TextureProvider textures)
	{
		int vertexCount = model.getVerticesCount();
		int faceCount = model.getFaceCount();
		if (vertexCount <= 0 || faceCount <= 0 || vertexCount > MAX_VERTICES || faceCount > MAX_FACES)
		{
			return null;
		}
		short[] textureIds = model.getFaceTextures();
		int[] textureColors = null;
		if (textureIds != null)
		{
			textureColors = new int[faceCount];
			for (int f = 0; f < faceCount; f++)
			{
				textureColors[f] = textureIds[f] == -1 ? -1 : textures.getDefaultColor(textureIds[f]);
			}
		}
		return of(vertexCount, model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
			faceCount, model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
			model.getFaceColors1(), model.getFaceColors2(), model.getFaceColors3(),
			model.getFaceTransparencies(), textureColors);
	}

	static CharacterModel of(int vertexCount, float[] x, float[] y, float[] z,
		int faceCount, int[] indices1, int[] indices2, int[] indices3,
		int[] colors1, int[] colors2, int[] colors3, byte[] transparencies, int[] textureColors)
	{
		short[] vertices = new short[vertexCount * 3];
		for (int v = 0; v < vertexCount; v++)
		{
			vertices[v * 3] = coordinate(x[v]);
			vertices[v * 3 + 1] = coordinate(y[v]);
			vertices[v * 3 + 2] = coordinate(z[v]);
		}

		int[] faces = new int[faceCount * 3];
		byte[] colors = new byte[faceCount * 9];
		byte[] alphas = new byte[faceCount];
		int kept = 0;
		for (int f = 0; f < faceCount; f++)
		{
			int a = colors1[f];
			int b = colors2[f];
			int c = colors3[f];
			if (c == -2)
			{
				continue;
			}
			int alpha = transparencies == null ? 255 : 255 - (transparencies[f] & 0xFF);
			if (alpha == 0)
			{
				continue;
			}
			int i1 = indices1[f];
			int i2 = indices2[f];
			int i3 = indices3[f];
			if (i1 < 0 || i2 < 0 || i3 < 0 || i1 >= vertexCount || i2 >= vertexCount || i3 >= vertexCount)
			{
				continue;
			}
			if (c == -1)
			{
				b = a;
				c = a;
			}
			int texture = textureColors == null ? -1 : textureColors[f];
			if (texture != -1)
			{
				a = lit(texture, a);
				b = lit(texture, b);
				c = lit(texture, c);
			}
			faces[kept * 3] = i1;
			faces[kept * 3 + 1] = i2;
			faces[kept * 3 + 2] = i3;
			putRgb(colors, kept * 9, a);
			putRgb(colors, kept * 9 + 3, b);
			putRgb(colors, kept * 9 + 6, c);
			alphas[kept] = (byte) alpha;
			kept++;
		}
		if (kept == 0)
		{
			return null;
		}
		return new CharacterModel(vertices, Arrays.copyOf(faces, kept * 3), Arrays.copyOf(colors, kept * 9), Arrays.copyOf(alphas, kept));
	}

	public int faceCount()
	{
		return alphas.length;
	}

	public byte[] encode()
	{
		int vertexCount = vertices.length / 3;
		int faceCount = faceCount();
		ByteBuffer out = ByteBuffer.allocate(8 + vertexCount * 6 + faceCount * 16).order(ByteOrder.LITTLE_ENDIAN);
		out.put(new byte[]{'R', 'J', 'M', '1'});
		out.putShort((short) vertexCount);
		out.putShort((short) faceCount);
		for (short v : vertices)
		{
			out.putShort(v);
		}
		for (int i : faces)
		{
			out.putShort((short) i);
		}
		out.put(colors);
		out.put(alphas);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(out.capacity() / 2);
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes))
		{
			gzip.write(out.array());
		}
		catch (IOException e)
		{
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	private static short coordinate(float v)
	{
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
	}

	private static int lit(int hsl, int light)
	{
		int lightness = (hsl & 127) * light >> 7;
		return (hsl & 0xFF80) | Math.max(2, Math.min(126, lightness));
	}

	private static void putRgb(byte[] out, int at, int hsl)
	{
		int rgb = rgb(hsl);
		out[at] = (byte) (rgb >> 16);
		out[at + 1] = (byte) (rgb >> 8);
		out[at + 2] = (byte) rgb;
	}

	static int rgb(int hsl)
	{
		double hue = ((hsl >> 10) & 63) / 64.0 + 0.0078125;
		double saturation = ((hsl >> 7) & 7) / 8.0 + 0.0625;
		double lightness = (hsl & 127) / 128.0;
		double q = lightness < 0.5 ? lightness * (1 + saturation) : lightness + saturation - lightness * saturation;
		double p = 2 * lightness - q;
		double red = hue + 1 / 3.0;
		double blue = hue - 1 / 3.0;
		return (channel(p, q, red > 1 ? red - 1 : red) << 16)
			| (channel(p, q, hue) << 8)
			| channel(p, q, blue < 0 ? blue + 1 : blue);
	}

	private static int channel(double p, double q, double t)
	{
		double v;
		if (6 * t < 1)
		{
			v = p + (q - p) * 6 * t;
		}
		else if (2 * t < 1)
		{
			v = q;
		}
		else if (3 * t < 2)
		{
			v = p + (q - p) * (2 / 3.0 - t) * 6;
		}
		else
		{
			v = p;
		}
		int c = (int) (Math.pow((int) (v * 256) / 256.0, BRIGHTNESS) * 256);
		return Math.max(0, Math.min(255, c));
	}
}
