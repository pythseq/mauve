package org.gel.mauve;

import java.io.IOException;
import java.io.Serializable;

import org.gel.mauve.backbone.Backbone;
import org.gel.mauve.backbone.BackboneList;

/**
 * SimilarityIndex generates a queryable in-memory index of the similarity among
 * a group of aligned sequences. This implementation uses the average entropy
 * over a sliding window of alignment columns
 */
public class SimilarityIndex extends ZoomHistogram implements Serializable {
	/** object format version */
	static final long serialVersionUID = 3;

	int index_factor = 8;

	/** < Index granularity grows in multiples of this number */
	int min_index_values = 500;

	/** < Never create a resolution level with fewer than this many values */
	int max_index_mb = 300;

	/** < Maximum size in MB for the index */

	int window_size = 100;

	/** < Average the similarity using a sliding window over this many columns */


	long seq_length;

	SimilarityIndex (Genome g, XMFAAlignment xmfa, BackboneList bb_list)
			throws IOException {
		this.seq_length = g.getLength ();
		allocateIndex ();
		calculateIndex (g, xmfa, bb_list);
	}

	/** Change the sequence indexed to seqI */
	public void setSequence (Genome g) {
		this.seq_length = g.getLength ();
	}

	/**
	 * Allocates space for the similarity index
	 */
	void allocateIndex () {
		// calculate the number of index levels
		long level_one = seq_length / max_resolution;
		long level_tmp = level_one;
		levels = seq_length > min_index_values ? 1 : 0;
		while (level_tmp > min_index_values) {
			level_tmp /= index_factor;
			levels++;
		}
		// nothing to allocate if there are no levels
		if (levels == 0)
			return;

		resolutions = new long [levels];
		index_size = new long [levels];
		level_tmp = level_one;
		long size_sum = 0;
		int levelI;
		long cur_resolution = max_resolution;
		for (levelI = 0; levelI < levels; levelI++) {
			resolutions[levelI] = cur_resolution;
			index_size[levelI] = level_tmp;
			size_sum += level_tmp;
			level_tmp /= index_factor;
			cur_resolution *= index_factor;
		}

		// check whether the index will fit in the max size
		if (size_sum > ((long) max_index_mb * 1024l * 1024l)) {
			throw new RuntimeException ("Similarity index is too large.");
		}

		values = new byte [(int) size_sum];
	}

	void advanceIndex (String [] cols, int [] col_index, int seq) {
		while (true) {
			if (cols[seq].charAt (col_index[seq]) == '\r'
					|| cols[seq].charAt (col_index[seq]) == '\n') {
				col_index[seq]++;
				continue;
			}
			break;
		}
	}

	void skipGapColumns (Genome g, Object [] cols, int [] col_index) {
		while (true) {
			for (int seqI = 0; seqI < cols.length; seqI++) {
				col_index[seqI]++; // automatically advance every sequence by
				// one character
				while (true) {
					// ensure the character is valid

					byte [] col = (byte []) cols[seqI];
					int index = col_index[seqI];
					byte c = col[index];
					if (c == '\r' || c == '\n') {
						col_index[seqI]++;
						continue;
					}
					break;
				}
			}
			// don't let the sequence of interest remain a gap
			if (((byte []) cols[g.getSourceIndex ()])[col_index[g
					.getSourceIndex ()]] == '-') {
				continue;
			}
			break;
		}
	}

	/**
	 * Calculates index values
	 */
	public void calculateIndex (Genome g, XMFAAlignment alignment,
			BackboneList bb_list) {
		// for each nucleotide in the window, calculate its column's entropy,
		// considering gaps as non-matching alignment characters
		// question: should gaps in seqI be ignored? yes... think HGT.
		long cur_offset = 0;
		long buffer_left = -1;
		long buffer_right = -1;
		long buffer_size = 100000;
		Object [] cols = null;
		double [] chars = new double [5];
		double [] entropies = null;

		calculateLog2Table ();
		calculateFracTable ();
		initCharMap ();

		for (int indexI = 0; indexI < index_size[0]; indexI++) {
			// calculate the left and right ends of the current sliding window
			long lend = cur_offset + 1;
			lend = lend < 1 ? 1 : lend;
			long rend = lend + max_resolution;
			if (rend > seq_length) {
				rend = seq_length;
				lend = rend - max_resolution;
				lend = lend < 1 ? 1 : lend;
			}
			// fill the alignment buffer if necessary
			if (rend > buffer_right) {
				// read in a new buffer
				buffer_left = lend;
				buffer_right = lend + buffer_size;
				if (buffer_right > seq_length) {
					buffer_right = seq_length;
					buffer_left = buffer_right - buffer_size;
					buffer_left = buffer_left < 1 ? 1 : buffer_left;
				}
				cols = alignment.getRange (g, buffer_left, buffer_right);

				Backbone bb = null;
				Backbone next_bb = null;
				if (bb_list != null) {
					// if we have backbone information then find out whether
					// we're
					// inside a backbone segment. if we're not in a bb segment,
					// find the next segment since querying repeatedly would be
					// too expensive
					bb = bb_list.getBackbone (g, buffer_left);
					if (bb == null)
						next_bb = bb_list.getNextBackbone (g, buffer_left);
				}
				// calculate entropies for this set of alignment columns
				// assume that newlines and gaps haven't been filtered
				int [] col_index = new int [cols.length];
				entropies = new double [((byte []) cols[g.getSourceIndex ()]).length];
				int ent_count = (int) (buffer_right - buffer_left);
				for (int entI = 0; entI < ent_count; entI++) {
					int i;
					for (i = 0; i < 5; i++)
						chars[i] = 0;

					// tally up character counts, using backbone info if
					// available
					if (bb_list == null) {
						// store nt frequencies as a, c, g, t, gap
						for (i = 0; i < cols.length; i++) {
							chars[char_map[(char) ((byte []) cols[i])[col_index[i]]]]++;
						}
					} else {
						// first check whether we're still in range of the
						// backbone
						// update the current bb segment as necessary
						if (bb != null
								&& buffer_left + entI > bb.getRightEnd (g)) {
							bb = bb_list.getBackbone (g, buffer_left + entI);
							if (bb == null)
								next_bb = bb_list.getNextBackbone (g,
										buffer_left + entI);
						} else if (bb == null
								&& next_bb != null
								&& next_bb.getLeftEnd (g) <= buffer_left + entI
								&& buffer_left + entI <= next_bb
										.getRightEnd (g)) {
							bb = next_bb;
							next_bb = null;
						}

						// use bb to inform...
						// if we're outside of bb just add this seq
						// if we're inside bb, add the seqs designated by the bb
						int sI = g.getSourceIndex ();
						if (bb == null) {
							chars[char_map[(char) ((byte []) cols[sI])[col_index[sI]]]]++;
							chars[char_map['-']] += cols.length - 1; // add
							// gap
							// for
							// the
							// rest
						} else {
							boolean seqs[] = bb.getSeqs ();
							for (i = 0; i < cols.length; i++) {
								if (seqs[i])
									chars[char_map[(char) ((byte []) cols[i])[col_index[i]]]]++;
							}
						}
					}

					entropies[entI] = 0;
					// count number of different characters in this column
					// each gap counts as a different type
					int char_types = 0;
					for (i = 0; i < 5; i++)
						char_types += chars[i];

					// calculate entropy
					for (i = 0; i < 4; i++) {
						if (chars[i] == 0)
							continue;
						entropies[entI] -= frac ((int) chars[i], char_types)
								* log_2 ((int) chars[i], char_types);
					}
					for (i = 0; i < chars[4]; i++) {
						entropies[entI] -= frac (1, char_types)
								* log_2 (1, char_types);
					}
					skipGapColumns (g, cols, col_index);
				}

			}

			// now calculate average entropy for each index value
			double entropy_sum = 0;
			int col_left = (int) (lend - buffer_left);
			int col_right = (int) (rend - buffer_left);
			for (int colI = col_left; colI < col_right; colI++) {
				entropy_sum += entropies[colI];
			}
			double tmp = (entropy_sum / (double) (col_right - col_left));
			tmp = 1 - tmp; // make lower entropy values higher
			tmp -= .5; // convert to a value between -128 and 127
			tmp *= 255;
			values[indexI] = (byte) tmp;
			if (tmp < -127)
				values[indexI] = -128;
			cur_offset += max_resolution;
		}

		// calculate subsequent levels using the previous level
		for (int levelI = 1; levelI < levels; levelI++) {
			int componentI = (int) getLevelOffset (levelI - 1);
			int level_offset = (int) getLevelOffset (levelI);
			for (int indexI = 0; indexI < index_size[levelI]; indexI++) {
				int sim_sum = 0;
				for (int subI = 0; subI < index_factor; subI++) {
					sim_sum += values[componentI];
					componentI++;
				}
				// set to the average of its components
				values[level_offset + indexI] = (byte) (sim_sum / index_factor);
			}
		}
	}


	int [] char_map = null;

	void initCharMap () {
		char_map = new int [128];
		char_map['a'] = 0;
		char_map['A'] = 0;
		char_map['c'] = 1;
		char_map['C'] = 1;
		char_map['g'] = 2;
		char_map['G'] = 2;
		char_map['t'] = 3;
		char_map['T'] = 3;
		char_map['-'] = 4;
		char_map['r'] = 0;
		char_map['R'] = 0;
		char_map['k'] = 2;
		char_map['K'] = 2;
		char_map['s'] = 1;
		char_map['S'] = 1;
		char_map['m'] = 0;
		char_map['M'] = 0;
		char_map['y'] = 1;
		char_map['Y'] = 1;
		char_map['w'] = 0;
		char_map['W'] = 0;
		char_map['b'] = 1;
		char_map['B'] = 1;
		char_map['v'] = 0;
		char_map['V'] = 0;
		char_map['d'] = 0;
		char_map['D'] = 0;
		char_map['h'] = 0;
		char_map['H'] = 0;
		// May as well treat these as gap characters since they
		// don't really affect the entropy
		char_map['n'] = 4;
		char_map['N'] = 4;
		char_map['x'] = 4;
		char_map['X'] = 4;
	}

	double fracs[][] = null;

	public void calculateFracTable () {
		// calculate the table
		fracs = new double [127] [127];

		for (double x = 1; x < 128; x++) {
			for (double y = 1; y < 128; y++) {
				fracs[(int) x - 1][(int) y - 1] = x / y;
			}
		}
	}

	double frac (int numerator, int denominator) {
		// if it's not in the matrix do it manually
		if (numerator < 1 || denominator < 1 || numerator > 127
				|| denominator > 127)
			return (double) numerator / (double) denominator;

		// otherwise return the pre-computed value
		return fracs[numerator - 1][denominator - 1];
	}

	/**
	 * Methods to precompute log values of x/y fractions where x and y range
	 * from 0-127 Intended to save time by replacing logarithm computation with
	 * a table lookup
	 */
	double logs[][] = null;

	public void calculateLog2Table () {
		// calculate the table
		logs = new double [127] [127];

		for (double x = 1; x < 128; x++) {
			for (double y = 1; y < 128; y++) {
				logs[(int) x - 1][(int) y - 1] = Math.log (x / y)
						/ Math.log (2d);
			}
		}
	}

	double log_2 (double numerator, double denominator) {
		return Math.log (numerator / denominator) / Math.log (2d);
	}

	double log_2 (int numerator, int denominator) {
		if (denominator == 0) {
			// return NaN
			return Double.NaN;
		}
		if (numerator == 0) {
			// return -inf
			return Double.MIN_VALUE;
		}
		// if it's not in the matrix do it manually
		if (numerator > 127 || denominator > 127)
			return Math.log ((double) numerator / (double) denominator)
					/ Math.log (2d);

		// otherwise return the pre-computed value
		return logs[numerator - 1][denominator - 1];
	}

}
