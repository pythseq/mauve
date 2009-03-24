package org.gel.mauve.operon;

/**
 * Compares two operons with respect to one specific feature
 *
 */
public interface OperonDiff {
	
	//should return null if operons are the same
	public boolean isSame (Operon one, int seq2);
	
	public String getFeature ();

}
