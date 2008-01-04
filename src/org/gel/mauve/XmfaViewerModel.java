package org.gel.mauve;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.Arrays;
import java.util.prefs.BackingStoreException;

import org.gel.mauve.backbone.BackboneList;
import org.gel.mauve.backbone.BackboneListBuilder;
import org.gel.mauve.color.BackboneLcbColor;
import org.gel.mauve.color.LCBColorScheme;
import org.gel.mauve.ext.MauveInterfacer;

/**
 * @author pinfield
 * 
 * A viewer model backed by an XMFA file. Models a global gapped sequence
 * alignment in an XMFA format file
 */
public class XmfaViewerModel extends LcbViewerModel {
	protected MauveAlignment xmfa;
	// Sequence similarity profiles calculated over the length of each sequence
	// represented in an XMFA file
	private ZoomHistogram [] sim;
	private long [] highlights;
	private BackboneList bb_list;
	private ObjectInputStream cache_instream;

	public void setSequenceCount (int sequenceCount) {
		super.setSequenceCount (sequenceCount);
		sim = new ZoomHistogram [sequenceCount];
	}

	public XmfaViewerModel (File src, ModelProgressListener listener)
			throws IOException {
		super (src);

		init (listener, false);
	}

    /**
     * @param listener
     * @throws FileNotFoundException
     * @throws IOException
     */
    protected void init(ModelProgressListener listener, boolean isReloading) throws FileNotFoundException, IOException
    {
        try {
			// attempt to load data such as SimilarityIndexes from a
			// disk-based cache file
			// Find cached directory, if it exists.
			URL xmfa_url = new URL("file://" + getSrc());
			File dir = null;
			if(ModelBuilder.getUseDiskCache())
			{
			    try
			    {
			        dir = ModelBuilder.getCachedDirectory(xmfa_url);
			    }
			    catch (BackingStoreException e)
			    {
			        System.err.println("Error reading preferences.  Error follows.  Will load from server.");
			        e.printStackTrace();
			    }
			}
			if( ModelBuilder.getUseDiskCache() && dir == null ){
			    // Create a temporary directory.
			    dir = File.createTempFile("mauve", "dir");
			    dir.delete();
			    dir.mkdir();
			    if (!dir.exists() || !dir.isDirectory())
			    {
			        throw new IOException("Couldn't create temporary directory.");
			    }
			    ModelBuilder.saveCachedDirectory(xmfa_url, dir);
			}
			
			if (listener != null)
			{
			    listener.alignmentStart();
			}

			// check whether XMFA has changed since the
			// cache was created!!
			// open object I/O for caching
			File cache_file = null;
			cache_instream = null;
			if(ModelBuilder.getUseDiskCache())
			{
			    cache_file = new File(dir, "mauve.cache");
			    if(cache_file.exists() && cache_file.canRead() &&
			    		getSrc().lastModified() < cache_file.lastModified())
			    {
			    	cache_instream = new ObjectInputStream(new java.io.FileInputStream(cache_file));
			    }
			}

			makeAlignment ();

			// If no sequences are found, this is certainly an invalid file.
			if (xmfa.seq_count == 0)
			{
			    throw new IOException("Not an XMFA file.  Please check that the" +
			    		" input file is a properly formatted alignment.");
			}
			
			if (listener != null)
			{
			    listener.alignmentEnd(xmfa.seq_count);
			}

			if (!isReloading)
			{
			    setSequenceCount(xmfa.seq_count);
			}

			// now build genomes
			for (int seqI = 0; seqI < xmfa.seq_count; seqI++)
			{
			    if (listener != null)
			    {
			        listener.featureStart(seqI);
			    }

			    Genome g = null;
			    if (!isReloading)
			    {
			        g = GenomeBuilder.buildGenome(seqI, this);
			        setGenome(seqI, g);
			    }
			    else
			    {
			        // If reloading, reorder the genomes to the same order as
			        // in the file.  reload() will take care of the reordering.
			    	g = getGenomeBySourceIndex(seqI);
			    }
			}
			
			// now try to read a backbone list
			makeBBList (cache_file);

			// now compute SimilarityIndex
			makeSimilarityIndex ();
			

			// if cache_instream is null there must have been a problem
			// reading the cache.  write out all objects that should be cached
			if(cache_instream == null && ModelBuilder.getUseDiskCache()){
				ObjectOutputStream cache_outstream = null;
				cache_outstream = new ObjectOutputStream(new FileOutputStream(cache_file));
				cache_outstream.writeObject(xmfa);
				for( int seqI = 0; seqI < xmfa.seq_count; seqI++ )
			    	cache_outstream.writeObject(sim[seqI]);
			}
      
			
			// copy the LCB list
			setFullLcbList(new LCB[xmfa.lcb_list.length]);
			System.arraycopy(xmfa.lcb_list, 0, getFullLcbList(), 0, xmfa.lcb_list.length);

			setDelLcbList(new LCB[0]);
			setLcbCount(getFullLcbList().length);
			
			highlights = new long[getSequenceCount()];
			Arrays.fill(highlights, Match.NO_MATCH);

			// set LCB colors first
			setColorScheme(new LCBColorScheme());
			if( bb_list != null )
				setColorScheme(new BackboneLcbColor());
			initModelLCBs();
			cache_instream = null;
		} catch (IOException e) {
	        cache_instream = null;
			throw e;
		}
    }

    protected void makeAlignment () 
    		throws IOException {
        RandomAccessFile inputFile = new RandomAccessFile(getSrc(), "r");
        
        // read XMFA from object cache if possible
        if(cache_instream != null){
            try{
            	xmfa = (XMFAAlignment)cache_instream.readObject();
            	((XMFAAlignment) xmfa).setFile(inputFile);
            }catch(ClassNotFoundException cnfe){
            	// cache must be corrupt
            	cache_instream = null;
            }catch(ClassCastException cce){
            	// cache must be corrupt
            	cache_instream = null;
            }catch(InvalidClassException ice){
            	cache_instream = null;
            }
        }
        // it didn't get read from the cache
        if(cache_instream == null)
            xmfa = new XMFAAlignment(inputFile);
    }
    
    protected void makeBBList (File cache_file) {
    	try{
    		bb_list = BackboneListBuilder.build(this,(XMFAAlignment) xmfa);
    		if( bb_list != null )
    		{
    			// if the backbone is newer than the cache then clear the cache
    			File bb_file = BackboneListBuilder.getBbFile(this,(XMFAAlignment) xmfa);
    			if(	ModelBuilder.getUseDiskCache() && bb_file.lastModified() > cache_file.lastModified())
    				cache_instream = null;
    		}
    	}catch(IOException ioe)
    	{
    		bb_list = null;
    	}
    }
    
    protected void makeSimilarityIndex () throws IOException {
		for (int seqI = 0; seqI < xmfa.seq_count; seqI++)
		{
		    Genome g = getGenomeBySourceIndex(seqI);
		    // read the SimilarityIndex from object cache if possible
		    if(cache_instream != null){
		        try{
		        	sim[seqI] = (SimilarityIndex)cache_instream.readObject();
		        }catch(ClassNotFoundException cnfe){
		        	// cache must be corrupt
		        	cache_instream = null;
		        }catch(ClassCastException cce){
		        	// cache must be corrupt
		        	cache_instream = null;
		        }catch(InvalidClassException ice){
		        	cache_instream = null;
		        }
		    }
		    // it didn't get read from the cache
		    if(cache_instream == null || sim [seqI] == null)
		    	sim[seqI] = new SimilarityIndex (g, xmfa, bb_list);
		}
    }
    
	protected void referenceUpdated () {
		super.referenceUpdated ();
		xmfa.setReference (getReference ());
	}

/**
     * 
     * @return
     */
    public MauveAlignment getXmfa()
    {
        return xmfa;
    }

	// NEWTODO: Sort sims on sourceIndex instead!
	public ZoomHistogram getSim (Genome g) {
		return sim[g.getSourceIndex ()];
	}
	
	public void setSim (Genome g, ZoomHistogram gram) {
		sim[g.getSourceIndex ()] = gram;
	}

	public long [] getLCBAndColumn (Genome g, long position) {
		return xmfa.getLCBAndColumn (g, position);
	}

	public int getLCBIndex (Genome g, long position) {
		return xmfa.getLCB (g, position);
	}

	/**
	 * The backbone list or null if none exists
	 * 
	 * @return The backbone list or null if none exists
	 */
	public BackboneList getBackboneList () {
		return bb_list;
	}

	/**
	 * 
	 * Returns column coordinates in source genome order
	 * 
	 * @param lcb
	 * @param column
	 * @return
	 */
	public void getColumnCoordinates (int lcb, long column, long [] seq_coords,
			boolean [] gap) {
		xmfa.getColumnCoordinates (this, lcb, column, seq_coords, gap);
	}

	@Override
	public void updateHighlight (Genome g, long coordinate) {
		highlights = null;
		// Wait til end to call super, since it fires event.
		super.updateHighlight (g, coordinate);
	}

	public long getHighlight (Genome g) {
		if (highlights == null) {
			long [] iv_col = getLCBAndColumn (getHighlightGenome (),
					getHighlightCoordinate ());
			boolean [] gap = new boolean [this.getSequenceCount ()];
			;
			highlights = new long [this.getSequenceCount ()];
			getColumnCoordinates ((int) iv_col[0], iv_col[1], highlights, gap);
			for (int i = 0; i < highlights.length; ++i) {
				highlights[i] = Math.abs (highlights[i]);
				if (gap[i])
					highlights[i] *= -1;
			}
		}
		return highlights[g.getSourceIndex ()];
	}

	/**
	 * aligns the display to a particular position of a particular sequence.
	 * typically called by RRSequencePanel when the user clicks a part of the
	 * sequence. Used for display mode 3
	 */
	@Override
	public void alignView (Genome g, long position) {
		long [] iv_col;
		try {
			iv_col = getLCBAndColumn (g, position);
		} catch (ArrayIndexOutOfBoundsException e) {
			// User clicked outside of bounds of sequence, so do nothing.
			return;
		}
		long [] coords = new long [this.getSequenceCount ()];
		;
		boolean [] gap = new boolean [this.getSequenceCount ()];
		;
		getColumnCoordinates ((int) iv_col[0], iv_col[1], coords, gap);
		alignView (coords, g);
	}

	/**
	 * Overrides setFocus() in BaseViewerModel to also align the display
	 * appropriately
	 */
	@Override
	public void setFocus (String sequenceID, long start, long end, String contig) {
		super.setFocus (sequenceID, start, end, contig);
		Genome g = null;
		for (int i = 0; i < genomes.length; i++) {
			if (sequenceID.equals (genomes[i].getID ())) {
				g = genomes[i];
				break;
			}
		}
		if (g == null) {
			System.err
					.println ("Received focus request for nonexistent sequence id "
							+ sequenceID);
			return;
		}
		if (contig != null)
			start = contig_handler.getPseudoCoord(g.getSourceIndex(), start, contig);
		alignView (g, start);
	}

	public void reload () {
		fireReloadStartEvent ();

		try {
			init (null, true);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace ();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace ();
		}

		fireReloadEndEvent ();
	}

	protected void fireReloadEndEvent () {
		// Guaranteed to return a non-null array
		Object [] listeners = listenerList.getListenerList ();
		// Process the listeners last to first, notifying
		// those that are interested in this event
		for (int i = listeners.length - 2; i >= 0; i -= 2) {
			if (listeners[i] == ModelListener.class) {
				((ModelListener) listeners[i + 1]).modelReloadEnd (modelEvent);
			}
		}
	}

	protected void fireReloadStartEvent () {
		// Guaranteed to return a non-null array
		Object [] listeners = listenerList.getListenerList ();
		// Process the listeners last to first, notifying
		// those that are interested in this event
		for (int i = listeners.length - 2; i >= 0; i -= 2) {
			if (listeners[i] == ModelListener.class) {
				((ModelListener) listeners[i + 1])
						.modelReloadStart (modelEvent);
			}
		}
	}
}
