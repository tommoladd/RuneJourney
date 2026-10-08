package com.runejourney.cloud;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import lombok.Data;

/**
 * Screenshots are uploaded as JPEGs (a fraction of the PNG's size) with a small thumbnail. Each file
 * starts with a header saying what it is, so another PC can show it in the gallery:
 * <pre>
 *   header length (4 bytes) | header JSON | JPEG
 * </pre>
 * The original PNG stays on the PC that took it.
 */
final class MediaCodec
{
	static final float QUALITY = 0.85f;
	static final int THUMB_WIDTH = 320;
	private static final float THUMB_QUALITY = 0.8f;
	private static final int MAX_HEADER = 4096;

	private MediaCodec()
	{
	}

	@Data
	static class Header
	{
		private String name;
		private String title;
		private long time;
		private int width;
		private int height;
	}

	@Data
	static class Decoded
	{
		private final Header header;
		private final byte[] jpeg;
	}

	/**
	 * The screenshot as a JPEG, made smaller until it fits.
	 */
	static byte[] full(Gson gson, BufferedImage image, Header header, int maxBytes) throws IOException
	{
		BufferedImage img = rgb(image);
		while (true)
		{
			byte[] file = file(gson, header, jpeg(img, QUALITY), img);
			if (file.length <= maxBytes || img.getWidth() < 200)
			{
				return file;
			}
			img = scale(img, (int) (img.getWidth() * 0.75));
		}
	}

	static byte[] thumb(Gson gson, BufferedImage image, Header header) throws IOException
	{
		BufferedImage img = scale(rgb(image), Math.min(THUMB_WIDTH, image.getWidth()));
		return file(gson, header, jpeg(img, THUMB_QUALITY), img);
	}

	static Decoded decode(Gson gson, byte[] file) throws IOException
	{
		if (file.length < 4)
		{
			throw new IOException("Screenshot file is too short");
		}
		int length = ByteBuffer.wrap(file, 0, 4).getInt();
		if (length < 2 || length > MAX_HEADER || 4 + length > file.length)
		{
			throw new IOException("Screenshot file has no header");
		}
		Header header = gson.fromJson(new String(file, 4, length, StandardCharsets.UTF_8), Header.class);
		return new Decoded(header, Arrays.copyOfRange(file, 4 + length, file.length));
	}

	private static byte[] file(Gson gson, Header header, byte[] jpeg, BufferedImage img)
	{
		Header h = new Header();
		h.setName(header.getName());
		h.setTitle(header.getTitle());
		h.setTime(header.getTime());
		h.setWidth(img.getWidth());
		h.setHeight(img.getHeight());
		byte[] json = gson.toJson(h).getBytes(StandardCharsets.UTF_8);
		ByteBuffer out = ByteBuffer.allocate(4 + json.length + jpeg.length);
		out.putInt(json.length).put(json).put(jpeg);
		return out.array();
	}

	static byte[] jpeg(BufferedImage img, float quality) throws IOException
	{
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext())
		{
			throw new IOException("No JPEG encoder");
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes))
		{
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(img, null, null), param);
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/**
	 * JPEG has no transparency: draws the image onto a plain RGB one.
	 */
	private static BufferedImage rgb(BufferedImage src)
	{
		if (src.getType() == BufferedImage.TYPE_INT_RGB)
		{
			return src;
		}
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}

	private static BufferedImage scale(BufferedImage src, int width)
	{
		int w = Math.max(1, width);
		int h = Math.max(1, (int) Math.round(src.getHeight() * (double) w / src.getWidth()));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}
}
