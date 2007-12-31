/* 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307 USA
*/
 
/* 
 * This software was written by Phillip Lord (p.lord@hgmp.mrc.ac.uk)
 * whilst at the University of Manchester as a Pfizer post-doctoral 
 * Research Fellow. 
 *
 * The initial code base is copyright by Pfizer, or the University
 * of Manchester. Modifications to the initial code base are copyright
 * of their respective authors, or their employers as appropriate. 
 * Authorship of the modifications may be determined from the ChangeLog
 * placed at the end of this file
 */

package uk.ac.man.bioinf.apps.cinema.core; // Package name inserted by JPack
import javax.swing.Action;

import uk.ac.man.bioinf.apps.cinema.utils.CinemaAlignmentFrame;

/**
 * CinemaFramedActionProvider.java
 *
 *
 * Created: Fri Oct 13 17:04:25 2000
 *
 * @author Phillip Lord
 * @version $Id: CinemaFramedActionProvider.java,v 1.2 2001/04/11 17:04:42 lord Exp $ 
 */

public interface CinemaFramedActionProvider
{
  public Action[] getActions( CinemaAlignmentFrame frame );
}// CinemaFramedActionProvider


/*
 * ChangeLog
 * $Log: CinemaFramedActionProvider.java,v $
 * Revision 1.2  2001/04/11 17:04:42  lord
 * Added License agreements to all code
 *
 * Revision 1.1  2000/10/19 17:39:12  lord
 * This provides access to the frame for a module. I am not sure I like
 * this yet.
 * 
 */

