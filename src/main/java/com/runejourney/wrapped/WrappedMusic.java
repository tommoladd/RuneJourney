package com.runejourney.wrapped;

import java.util.Random;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Track;
import lombok.extern.slf4j.Slf4j;

/**
 * Original background music for Wrapped, composed on the fly in the style of OSRS's MIDI soundtrack.
 * Each theme has its own instruments, key, tempo and progression; the melody varies with the week.
 * Plays through Java's built-in synthesizer, so no audio files are bundled.
 */
@Slf4j
public final class WrappedMusic
{
	private static final int PPQ = 480;
	private static final int BAR = PPQ * 4;

	/**
	 * A theme: General MIDI programs for [pad, arpeggio, melody, bass], tempo, key root and chords
	 * (as scale degrees, 0-based, in a major or minor scale).
	 */
	private static final class Theme
	{
		final int[] programs;
		final int bpm;
		final int root;
		final boolean minor;
		final int[] progression;
		final boolean timpani;

		Theme(int[] programs, int bpm, int root, boolean minor, int[] progression, boolean timpani)
		{
			this.programs = programs;
			this.bpm = bpm;
			this.root = root;
			this.minor = minor;
			this.progression = progression;
			this.timpani = timpani;
		}
	}

	private static final Theme[] THEMES = {
		// Adventure: strings, harp and flute, like a trip across Misthalin
		new Theme(new int[]{48, 46, 73, 32}, 96, 62, false, new int[]{0, 4, 5, 3, 0, 4, 3, 4}, false),
		// Fanfare: horns and trumpet with timpani
		new Theme(new int[]{60, 48, 56, 58}, 108, 58, false, new int[]{0, 3, 4, 0, 5, 3, 4, 4}, true),
		// Mystic: music box and pan flute in a minor key
		new Theme(new int[]{49, 10, 75, 32}, 84, 57, true, new int[]{0, 5, 2, 6, 0, 5, 3, 4}, false),
		// Tavern: pizzicato, accordion and oboe
		new Theme(new int[]{21, 45, 68, 33}, 120, 55, false, new int[]{0, 3, 0, 4, 0, 3, 4, 0}, false),
		// Wilderness: dark strings and brass
		new Theme(new int[]{49, 46, 61, 43}, 90, 52, true, new int[]{0, 6, 5, 4, 0, 6, 5, 4}, true),
	};

	private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
	private static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10};

	private Sequencer sequencer;
	private Synthesizer synth;

	/**
	 * Starts looping the theme. Opening the synthesizer can take a moment, so call this off the
	 * client and Swing threads.
	 */
	public synchronized void play(int theme, long seed, int volumePercent)
	{
		stop();
		try
		{
			Sequence seq = compose(THEMES[Math.floorMod(theme, THEMES.length)], seed, volumePercent);
			synth = MidiSystem.getSynthesizer();
			synth.open();
			sequencer = MidiSystem.getSequencer(false);
			sequencer.open();
			sequencer.getTransmitter().setReceiver(synth.getReceiver());
			sequencer.setSequence(seq);
			sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
			sequencer.start();
		}
		catch (Exception e)
		{
			log.debug("Unable to play Wrapped music", e);
			stop();
		}
	}

	public synchronized void stop()
	{
		if (sequencer != null)
		{
			try
			{
				sequencer.stop();
			}
			catch (IllegalStateException ignored)
			{
				// already closed
			}
			sequencer.close();
			sequencer = null;
		}
		if (synth != null)
		{
			synth.close();
			synth = null;
		}
	}

	/**
	 * Composes a theme without playing it.
	 */
	static Sequence compose(int theme, long seed, int volumePercent) throws Exception
	{
		return compose(THEMES[Math.floorMod(theme, THEMES.length)], seed, volumePercent);
	}

	private static Sequence compose(Theme t, long seed, int volumePercent) throws Exception
	{
		Random rnd = new Random(seed);
		Sequence seq = new Sequence(Sequence.PPQ, PPQ);
		Track track = seq.createTrack();
		int[] scale = t.minor ? MINOR : MAJOR;
		int volume = Math.max(0, Math.min(127, volumePercent * 127 / 100));

		// Tempo
		int mpq = 60_000_000 / t.bpm;
		javax.sound.midi.MetaMessage tempo = new javax.sound.midi.MetaMessage(0x51,
			new byte[]{(byte) (mpq >> 16), (byte) (mpq >> 8), (byte) mpq}, 3);
		track.add(new MidiEvent(tempo, 0));

		// Channels: 0 pad, 1 arpeggio, 2 melody, 3 bass, 4 timpani
		for (int ch = 0; ch < 4; ch++)
		{
			track.add(event(ShortMessage.PROGRAM_CHANGE, ch, t.programs[ch], 0, 0));
			track.add(event(ShortMessage.CONTROL_CHANGE, ch, 7, volume, 0));
		}
		track.add(event(ShortMessage.PROGRAM_CHANGE, 4, 47, 0, 0));
		track.add(event(ShortMessage.CONTROL_CHANGE, 4, 7, volume, 0));

		int bars = t.progression.length * 2;
		int lastMelody = -1;
		for (int bar = 0; bar < bars; bar++)
		{
			int degree = t.progression[bar % t.progression.length];
			int[] chord = {
				note(t.root, scale, degree), note(t.root, scale, degree + 2), note(t.root, scale, degree + 4)
			};
			long start = (long) bar * BAR;

			// Pad: sustained chord
			for (int n : chord)
			{
				add(track, 0, n - 12, 52, start, BAR - 20);
			}
			// Bass: root on beats 1 and 3
			add(track, 3, chord[0] - 24, 80, start, PPQ * 2 - 20);
			add(track, 3, chord[0] - 24, 70, start + PPQ * 2, PPQ * 2 - 20);

			// Arpeggio: eighth notes up and down the chord
			int[] pattern = {0, 1, 2, 1, 0, 1, 2, 1};
			for (int i = 0; i < 8; i++)
			{
				int n = chord[pattern[i]] + (i >= 4 && rnd.nextBoolean() ? 12 : 0);
				add(track, 1, n, 58 + rnd.nextInt(12), start + (long) i * PPQ / 2, PPQ / 2 - 10);
			}

			// Melody: chord tones with passing notes, a phrase every two bars
			boolean restBar = bar % 8 == 7;
			long pos = start;
			while (pos < start + BAR && !restBar)
			{
				int len = rnd.nextInt(4) == 0 ? PPQ : rnd.nextInt(3) == 0 ? PPQ * 3 / 2 : PPQ / 2;
				if (pos + len > start + BAR)
				{
					len = (int) (start + BAR - pos);
				}
				int n;
				if (lastMelody < 0 || rnd.nextInt(3) > 0)
				{
					n = chord[rnd.nextInt(3)] + 12;
				}
				else
				{
					// Step to a neighbouring scale note for a singable line
					int deg = degreeOf(t.root, scale, lastMelody) + (rnd.nextBoolean() ? 1 : -1);
					n = note(t.root, scale, deg);
				}
				if (rnd.nextInt(7) > 0)
				{
					add(track, 2, n, 88 + rnd.nextInt(16), pos, len - 15);
					lastMelody = n;
				}
				pos += len;
			}

			// Timpani on the downbeat of each phrase
			if (t.timpani && bar % 2 == 0)
			{
				add(track, 4, chord[0] - 24, 100, start, PPQ);
			}
		}

		// Final chord to round off each loop
		long end = (long) bars * BAR;
		track.add(event(ShortMessage.CONTROL_CHANGE, 0, 123, 0, end));
		return seq;
	}

	private static int note(int root, int[] scale, int degree)
	{
		int octave = Math.floorDiv(degree, 7);
		return root + scale[Math.floorMod(degree, 7)] + 12 * octave;
	}

	private static int degreeOf(int root, int[] scale, int midi)
	{
		int rel = midi - root;
		int octave = Math.floorDiv(rel, 12);
		int pitch = Math.floorMod(rel, 12);
		int best = 0;
		for (int i = 0; i < 7; i++)
		{
			if (Math.abs(scale[i] - pitch) < Math.abs(scale[best] - pitch))
			{
				best = i;
			}
		}
		return best + octave * 7;
	}

	private static void add(Track track, int channel, int note, int velocity, long tick, long length) throws Exception
	{
		int n = Math.max(24, Math.min(108, note));
		track.add(event(ShortMessage.NOTE_ON, channel, n, velocity, tick));
		track.add(event(ShortMessage.NOTE_OFF, channel, n, 0, tick + Math.max(10, length)));
	}

	private static MidiEvent event(int command, int channel, int data1, int data2, long tick) throws Exception
	{
		ShortMessage m = new ShortMessage();
		m.setMessage(command, channel, data1, data2);
		return new MidiEvent(m, tick);
	}
}
