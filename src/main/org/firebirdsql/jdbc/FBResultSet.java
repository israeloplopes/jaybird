/*
 * Firebird Open Source J2ee connector - jdbc driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a CVS history command.
 *
 * All rights reserved.
 */

package org.firebirdsql.jdbc;


import java.io.*;
import java.math.*;
import java.net.*;
import java.sql.*;
import java.sql.Date;
import java.util.*;

import org.firebirdsql.gds.*;
import org.firebirdsql.jdbc.field.*;


/**
 * Implementation of {@link ResultSet} interface.
 *
 * @author <a href="mailto:d_jencks@users.sourceforge.net">David Jencks</a>
 * @author <a href="mailto:rrokytskyy@users.sourceforge.net">Roman Rokytskyy</a>
 */
public class FBResultSet implements ResultSet {

    private FBFetcher fbFetcher;

    protected AbstractConnection c;

    public XSQLVAR[] xsqlvars;

    public byte[][] row = null;

    private int maxRows = 0;
    private int fetchSize = 0;
     
    private boolean wasNull = false;
    private boolean wasNullValid = false;
    // closed is false until the close method is invoked;
    private boolean closed = false;

    //might be a bit of a kludge, or a useful feature.
    private boolean trimStrings;

    private SQLWarning firstWarning = null;
     
    private FBField[] fields = null;
    private java.util.HashMap colNames = new java.util.HashMap();
    
    private String cursorName;
    private FBObjectListener.ResultSetListener listener;
    
    private int rsType = ResultSet.TYPE_FORWARD_ONLY;
    private int rsConcurrency = ResultSet.CONCUR_READ_ONLY;
    
    private boolean paranoiaModa;
    
	 /**
     * Creates a new <code>FBResultSet</code> instance.
     *
     * @param c a <code>AbstractConnection</code> value
     * @param fbstatement a <code>AbstractStatement</code> value
     * @param stmt an <code>isc_stmt_handle</code> value
     */
    protected FBResultSet(AbstractConnection c, 
                          AbstractStatement fbstatement, 
                          isc_stmt_handle stmt, 
                          FBObjectListener.ResultSetListener listener,
                          boolean trimStrings, 
                          int rsType, 
                          int rsConcurrency,
                          boolean cached) 
    throws SQLException {
        
        this.c = c;
        this.cursorName = fbstatement.getCursorName();
        
        this.listener = listener;
        
        this.rsType = rsType;
        this.rsConcurrency = rsConcurrency;
        
        this.trimStrings = trimStrings;
        
        // check if we are running in paranoia mode
        checkParanoiaMode(c);
        
        this.xsqlvars = stmt.getOutSqlda().sqlvar;
        this.maxRows = fbstatement.getMaxRows();
        
        boolean updatableCursor = fbstatement.isUpdatableCursor();

        //prepareVars((!updatableCursor && rsType == ResultSet.TYPE_SCROLL_INSENSITIVE) || cached);
        
        if (cached) {
            prepareVars(true);
            fbFetcher = new FBCachedFetcher(this.c, fbstatement, stmt, this);
        } else
        if (!updatableCursor && rsType == ResultSet.TYPE_SCROLL_INSENSITIVE) {
            prepareVars(true);
            fbFetcher = new FBCachedFetcher(this.c, fbstatement, stmt, this);
        } else {
            prepareVars(false);
            
            if (rsConcurrency == ResultSet.CONCUR_UPDATABLE) {
                c.addWarning(new FBSQLWarning(
                    "Result set concurrency changed. " +
                    "Only ResultSet.CONCUR_READ_ONLY concurrency is supported."));
                    
                rsConcurrency = ResultSet.CONCUR_READ_ONLY;
            }
                    
            if (rsType == ResultSet.TYPE_SCROLL_SENSITIVE) {
                c.addWarning(new FBSQLWarning(
                    "Result set type changed. " +
                    "ResultSet.TYPE_SCROLL_SENSITIVE is not supported."));
                    
                rsType = ResultSet.TYPE_SCROLL_INSENSITIVE;
            }
            
            if (updatableCursor)  
                fbFetcher = new FBUpdatableFetcher(this.c, fbstatement, stmt, this);
            else
                fbFetcher = new FBStatementFetcher(this.c, fbstatement, stmt, this);
        }
    }

    /*
     * Creates an instance of this class and caches complete result set for
     * later use. This constructor should be used only in auto-commit case.
     * 
     * @param c active database connection
     * @param stmt statement handle
     * @param trimStrings <code>true</code> if we should trim strings (used 
     * in {@link FBDatabaseMetaData} class).
     * @throws SQLException if database access error occurs
     */
//    protected FBResultSet(AbstractConnection c, AbstractStatement fbStatement,isc_stmt_handle stmt, 
//        boolean trimStrings, FBObjectListener.ResultSetListener listener) 
//        throws SQLException 
//    {
//        this.c = c;
//        this.trimStrings = trimStrings;
//        this.listener = listener;
//        
//        checkParanoiaMode(c);
//        
//        maxRows = fbStatement.getMaxRows();
//        xsqlvars = stmt.getOutSqlda().sqlvar;
//        prepareVars(true);
//        fbFetcher = new FBCachedFetcher(this.c, fbStatement, stmt, this);
//    }

    protected FBResultSet(XSQLVAR[] xsqlvars, ArrayList rows) throws SQLException {
        maxRows = 0;
        fbFetcher = new FBCachedFetcher(rows,this);
        this.xsqlvars = xsqlvars;
        prepareVars(true);
    }
    
    private void checkParanoiaMode(AbstractConnection c) {
        DatabaseParameterBuffer dpb = c.getDatabaseParameterBuffer();
        paranoiaModa = dpb.hasArgument(DatabaseParameterBuffer.paranoia_mode);
    }

    private void prepareVars(boolean cached) throws SQLException {
        fields = new FBField[xsqlvars.length];
        colNames = new HashMap(xsqlvars.length,1);
        for (int i=0; i<xsqlvars.length; i++){
            fields[i] = FBField.createField(xsqlvars[i], this, i, cached);
            fields[i].setConnection(c);
        }
    }

    /**
     * Check if statement is open and prepare statement for cursor move.
     * 
     * @throws SQLException if statement is closed.
     */
    protected void checkCursorMove() throws SQLException {
        if (closed) throw new FBSQLException("The resultSet is closed");
        wasNullValid = false;

        // close current fields, so that resources are freed.
        for(int i = 0; i < fields.length; i++) 
            fields[i].close();
    }

    /**
     * Moves the cursor down one row from its current position.
     * A <code>ResultSet</code> cursor is initially positioned
     * before the first row; the first call to the method
     * <code>next</code> makes the first row the current row; the
     * second call makes the second row the current row, and so on.
     *
     * <P>If an input stream is open for the current row, a call
     * to the method <code>next</code> will
     * implicitly close it. A <code>ResultSet</code> object's
     * warning chain is cleared when a new row is read.
     *
     * @return <code>true</code> if the new current row is valid;
     * <code>false</code> if there are no more rows
     * @exception SQLException if a database access error occurs
     */
    public boolean next() throws  SQLException {
        checkCursorMove();
        return fbFetcher.next();
    }



    /**
     * Releases this <code>ResultSet</code> object's database and
     * JDBC resources immediately instead of waiting for
     * this to happen when it is automatically closed.
     *
     * <P><B>Note:</B> A <code>ResultSet</code> object
     * is automatically closed by the
     * <code>Statement</code> object that generated it when
     * that <code>Statement</code> object is closed,
     * re-executed, or is used to retrieve the next result from a
     * sequence of multiple results. A <code>ResultSet</code> object
     * is also automatically closed when it is garbage collected.
     *
     * @exception SQLException if a database access error occurs
     */
    public void close() throws  SQLException {
        if (closed && paranoiaModa) throw new FBSQLException("The resultSet is closed");
        wasNullValid = false;
        closed = true;
        
        try {
            
            for(int i = 0; i < fields.length; i++)
                fields[i].close();
            
        } finally {
            fbFetcher.close();
        }
        
        if (listener != null)
            listener.resultSetClosed(this);
    }


    /**
     * Reports whether
     * the last column read had a value of SQL <code>NULL</code>.
     * Note that you must first call one of the <code>getXXX</code> methods
     * on a column to try to read its value and then call
     * the method <code>wasNull</code> to see if the value read was
     * SQL <code>NULL</code>.
     *
     * @return <code>true</code> if the last column value read was SQL
     *         <code>NULL</code> and <code>false</code> otherwise
     * @exception SQLException if a database access error occurs
     */
    public boolean wasNull() throws  SQLException {
        if (!wasNullValid) {
            throw new FBSQLException("Look at a column before testing null.");
        }
        if (row == null) {
            throw new FBSQLException("No row available for wasNull.");
        }
        return wasNull;		  
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * ResultSet object as a stream of ASCII characters. The value can then be 
     * read in chunks from the stream. This method is particularly suitable 
     * for retrieving large LONGVARCHAR values. 
     *
     * @param columnIndex The index of the parameter to retrieve, first 
     * parameter is 1, second is 2, ...
     * @return a stream of ascii characters
     * @throws SQLException if this parameter cannot be retrieved as an ASCII
     * stream
     */
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        return getField(columnIndex).getAsciiStream();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a BigDecimal object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The value of the field as a BigDecimal
     * @throws SQLException if this paramater cannot be retrieved as 
     * a BigDecimal
     */
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return getField(columnIndex).getBigDecimal();
    }


    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a binary InputStream.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The value of the field as a binary input stream 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a binary InputStream 
     */
public InputStream getBinaryStream(int columnIndex) throws SQLException {
        return getField(columnIndex).getBinaryStream();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a Blob object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The value of the field as a Blob object 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a Blob 
     */
    public Blob getBlob(int columnIndex) throws SQLException {
        return getField(columnIndex).getBlob();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>boolean</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>boolean</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>boolean</code>
     */
    public boolean getBoolean(int columnIndex) throws SQLException {
        return getField(columnIndex).getBoolean();
    }


    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>byte</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>byte</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>byte</code>
     */
    public byte getByte(int columnIndex) throws SQLException {
        return getField(columnIndex).getByte();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>byte</code> array.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>byte</code> array value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>byte</code> array
     */
    public byte[] getBytes(int columnIndex) throws SQLException {
        return getField(columnIndex).getBytes();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>Date</code> object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>Date</code> object of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>Date</code> object
     */
public Date getDate(int columnIndex) throws SQLException {
        return getField(columnIndex).getDate();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>double</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>double</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>double</code>
     */
    public double getDouble(int columnIndex) throws SQLException {
        return getField(columnIndex).getDouble();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>float</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>float</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>float</code>
     */
    public float getFloat(int columnIndex) throws SQLException {
        return getField(columnIndex).getFloat();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as an <code>int</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>int</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * an <code>int</code>
     */
    public int getInt(int columnIndex) throws SQLException {
        return getField(columnIndex).getInt();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>long</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>long</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>long</code>
     */
    public long getLong(int columnIndex) throws SQLException {
        return getField(columnIndex).getLong();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as an <code>Object</code>.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>Object</code> representation of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * an <code>Object</code>
     */
    public Object getObject(int columnIndex) throws SQLException {
        return getField(columnIndex).getObject();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>short</code> value.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>short</code> value of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>short</code>
     */
    public short getShort(int columnIndex) throws SQLException {
        return getField(columnIndex).getShort();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>String</code> object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>String</code> representation of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>String</code>
     */
    public String getString(int columnIndex) throws SQLException {
        if (trimStrings) {
            String result = getField(columnIndex).getString();
            return result != null ? result.trim() : null;
        } else
            return getField(columnIndex).getString();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>Time</code> object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>Time</code> representation of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>Time</code> object
     */
    public Time getTime(int columnIndex) throws SQLException {
        return getField(columnIndex).getTime();
    }

    /**
     * Retrieve the value of the designated column in the current row of
     * this ResultSet as a <code>Timestamp</code> object.
     *
     * @param columnIndex The index of the parameter to retrieve, first
     * parameter is 1, second is 2, ...
     * @return The <code>Timestamp</code> representation of the field 
     * @throws SQLException if this paramater cannot be retrieved as 
     * a <code>Timestamp</code> object
     */
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return getField(columnIndex).getTimestamp();
    }

    /**
     * @deprecated
     */
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        return getField(columnIndex).getUnicodeStream();
    }

    /**
     * Returns the XSQLVAR structure for the specified column.
     */
    protected XSQLVAR getXsqlvar(int columnIndex) {
        return xsqlvars[columnIndex - 1];
    }

    /**
     * Get the <code>FBField</code> object at the given column index
     *
     * @param columnIndex The index of the parameter, 1 is the first index
     * @throws SQLException If there is an error accessing the field
     */
    public FBField getField(int columnIndex) throws SQLException {
        FBField field = getField(columnIndex, true);

        wasNullValid = true;
        // wasNull = field.isNull();
        wasNull = (row[columnIndex - 1] == null);
        
        return field;
    }
    
    /**
     * Factory method for the field access objects
     */
    public FBField getField(int columnIndex, boolean checkRowPosition) throws SQLException {
        if (closed) throw new FBSQLException("The resultSet is closed");
        
        if (checkRowPosition && row == null)
            throw new FBSQLException(
                    "The resultSet is not in a row, use next",
                    FBSQLException.SQL_STATE_NO_ROW_AVAIL);
        
        if (columnIndex > xsqlvars.length)
             throw new FBSQLException(
                    "Invalid column index.",
                    FBSQLException.SQL_STATE_INVALID_COLUMN);
        
        return fields[columnIndex-1];
    }

    /**
     * Get a <code>FBField</code> by name.
     *
     * @param columnName The name of the field to be retrieved
     * @throws SQLException if the field cannot be retrieved
     */
    public FBField getField(String columnName) throws SQLException {
        if (closed) throw new FBSQLException("The resultSet is closed");
        
        if (row == null)
            throw new FBSQLException(
                    "The resultSet is not in a row, use next",
                    FBSQLException.SQL_STATE_NO_ROW_AVAIL);

        if (columnName == null) {
            throw new FBSQLException(
                    "Column identifier must be not null.",
                    FBSQLException.SQL_STATE_INVALID_COLUMN);
        }

        Integer fieldNum = (Integer) colNames.get(columnName);
        // If it is the first time the columnName is used
        if (fieldNum == null){
            int colNum = findColumn(columnName);
            fieldNum = new Integer(colNum);
            colNames.put(columnName, fieldNum);
        }
        int colNum = fieldNum.intValue();
        FBField field = fields[colNum - 1];
        wasNullValid = true;
        wasNull = (row[colNum - 1] == null);
        return field;
    }
     /**
     * Gets the value of the designated column in the current row
     * of this <code>ResultSet</code> object as
     * a <code>java.math.BigDecimal</code> in the Java programming language.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param scale the number of digits to the right of the decimal point
     * @return the column value; if the value is SQL <code>NULL</code>, the
     * value returned is <code>null</code>
     * @exception SQLException if a database access error occurs
     * @deprecated
     */
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws  SQLException {
        return getField(columnIndex).getBigDecimal(scale);
    }




    //======================================================================
    // Methods for accessing results by column name
    //======================================================================

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>String</code>. 
     *
     * @param columnName The SQL name of the column
     * @throws SQLException if the given column cannot be retrieved
     */
    public String getString(String columnName) throws  SQLException {
        if (trimStrings) {
            String result = getField(columnName).getString();
            return result != null ? result.trim() : null;
        } else
            return getField(columnName).getString();
    }


    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>boolean</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>String</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public boolean getBoolean(String columnName) throws  SQLException {
        return getField(columnName).getBoolean();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>byte</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>byte</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public byte getByte(String columnName) throws  SQLException {
        return getField(columnName).getByte();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>short</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return THe <code>short</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public short getShort(String columnName) throws  SQLException {
        return getField(columnName).getShort();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as an <code>int</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>int</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public int getInt(String columnName) throws  SQLException {
        return getField(columnName).getInt();
    }


    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>long</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>long</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public long getLong(String columnName) throws  SQLException {
        return getField(columnName).getLong();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>float</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>float</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public float getFloat(String columnName) throws  SQLException {
        return getField(columnName).getFloat();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>double</code> value. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>double</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public double getDouble(String columnName) throws  SQLException {
        return getField(columnName).getDouble();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>BigDecimal</code>. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>BigDecimal</code> value
     * @throws SQLException if the given column cannot be retrieved
     * @deprecated
     */
    public BigDecimal getBigDecimal(String columnName, int scale) throws  SQLException {
        return getField(columnName).getBigDecimal(scale);
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>byte</code> array. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>byte</code> array value
     * @throws SQLException if the given column cannot be retrieved
     */
    public byte[] getBytes(String columnName) throws  SQLException {
        return getField(columnName).getBytes();
    }


    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>Date</code>. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>Date</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public Date getDate(String columnName) throws  SQLException {
        return getField(columnName).getDate();
    }


    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>Time</code> object. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>Time</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public Time getTime(String columnName) throws  SQLException {
        return getField(columnName).getTime();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a <code>Timestamp</code> object. 
     *
     * @param columnName The SQL name of the column
     * @return The <code>Timestamp</code> value
     * @throws SQLException if the given column cannot be retrieved
     */
    public Timestamp getTimestamp(String columnName) throws  SQLException {
        return getField(columnName).getTimestamp();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as an <code>InputStream</code>. 
     *
     * @param columnName The SQL name of the column
     * @return The value as an <code>InputStream</code> 
     * @throws SQLException if the given column cannot be retrieved
     */
    public InputStream getAsciiStream(String columnName) throws  SQLException {
        return getField(columnName).getAsciiStream();
    }

    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a unicode <code>InputStream</code>. 
     *
     * @param columnName The SQL name of the column
     * @return The value as a unicode <code>InputStream</code> 
     * @throws SQLException if the given column cannot be retrieved
     * @deprecated
     */
    public InputStream getUnicodeStream(String columnName) throws  SQLException {
        return getField(columnName).getUnicodeStream();
    }


    /**
     * Retrieves the value of the designated column in the current row of this 
     * <code>ResultSet</code> object as a binary <code>InputStream</code>. 
     *
     * @param columnName The SQL name of the column
     * @return The value as a binary <code>InputStream</code> 
     * @throws SQLException if the given column cannot be retrieved
     */
    public InputStream getBinaryStream(String columnName) throws  SQLException {
        return getField(columnName).getBinaryStream();
    }



    //=====================================================================
    // Advanced features:
    //=====================================================================

    /**
     * Returns the first warning reported by calls on this
     * <code>ResultSet</code> object.
     * Subsequent warnings on this <code>ResultSet</code> object
     * will be chained to the <code>SQLWarning</code> object that
     * this method returns.
     *
     * <P>The warning chain is automatically cleared each time a new
     * row is read.
     *
     * <P><B>Note:</B> This warning chain only covers warnings caused
     * by <code>ResultSet</code> methods.  Any warning caused by
     * <code>Statement</code> methods
     * (such as reading OUT parameters) will be chained on the
     * <code>Statement</code> object.
     *
     * @return the first <code>SQLWarning</code> object reported or <code>null</code>
     * @exception SQLException if a database access error occurs
     */
    public SQLWarning getWarnings() throws  SQLException {
       return firstWarning;
    }


    /**
     * Clears all warnings reported on this <code>ResultSet</code> object.
     * After this method is called, the method <code>getWarnings</code>
     * returns <code>null</code> until a new warning is
     * reported for this <code>ResultSet</code> object.
     *
     * @exception SQLException if a database access error occurs
     */
    public void clearWarnings() throws  SQLException {
       firstWarning = null;
    }


    /**
     * Gets the name of the SQL cursor used by this <code>ResultSet</code>
     * object.
     *
     * <P>In SQL, a result table is retrieved through a cursor that is
     * named. The current row of a result set can be updated or deleted
     * using a positioned update/delete statement that references the
     * cursor name. To insure that the cursor has the proper isolation
     * level to support update, the cursor's <code>select</code> statement should be
     * of the form 'select for update'. If the 'for update' clause is
     * omitted, the positioned updates may fail.
     *
     * <P>The JDBC API supports this SQL feature by providing the name of the
     * SQL cursor used by a <code>ResultSet</code> object.
     * The current row of a <code>ResultSet</code> object
     * is also the current row of this SQL cursor.
     *
     * <P><B>Note:</B> If positioned update is not supported, a
     * <code>SQLException</code> is thrown.
     *
     * @return the SQL name for this <code>ResultSet</code> object's cursor
     * @exception SQLException if a database access error occurs
     */
    public String getCursorName() throws  SQLException {
        return cursorName;
    }


    /**
     * Retrieves the  number, types and properties of
     * this <code>ResultSet</code> object's columns.
     *
     * @return the description of this <code>ResultSet</code> object's columns
     * @exception SQLException if a database access error occurs
     * 
     * @todo we need another way of specifying the exended metadata if
     * this result set is constructed in code.
     */
    public ResultSetMetaData getMetaData() throws  SQLException {
        return new FBResultSetMetaData(xsqlvars, c);
    }



    /**
     * <p>Gets the value of the designated column in the current row
     * of this <code>ResultSet</code> object as
     * an <code>Object</code> in the Java programming language.
     *
     * <p>This method will return the value of the given column as a
     * Java object.  The type of the Java object will be the default
     * Java object type corresponding to the column's SQL type,
     * following the mapping for built-in types specified in the JDBC
     * specification.
     *
     * <p>This method may also be used to read datatabase-specific
     * abstract data types.
     *
     * In the JDBC 2.0 API, the behavior of the method
     * <code>getObject</code> is extended to materialize
     * data of SQL user-defined types.  When a column contains
     * a structured or distinct value, the behavior of this method is as
     * if it were a call to: <code>getObject(columnIndex,
     * this.getStatement().getConnection().getTypeMap())</code>.
     *
     * @param columnName the SQL name of the column
     * @return a <code>java.lang.Object</code> holding the column value
     * @exception SQLException if a database access error occurs
     */
    public Object getObject(String columnName) throws  SQLException {
        return getField(columnName).getObject();
    }


    //----------------------------------------------------------------

    /**
     * Maps the given <code>ResultSet</code> column name to its
     * <code>ResultSet</code> column index.
     *
     * @param columnName the name of the column
     * @return the column index of the given column name
     * @exception SQLException if a database access error occurs
     */
    // See section 14.2.3 of jdbc-3.0 specification
    // "Column names supplied to getter methods are case insensitive
    // If a select list contains the same column more than once, 
    // the first instance of the column will be returned
    public int findColumn(String columnName) throws SQLException {
        if (columnName == null || columnName.equals("")) {
            throw new FBSQLException(
                    "Empty string does not identify column.",
                    FBSQLException.SQL_STATE_INVALID_COLUMN);
        }
        if (columnName.startsWith("\"") && columnName.endsWith("\"")) {
            columnName = columnName.substring(1, columnName.length() - 1);				
            // case-sensitively check column aliases 
            for (int i = 0; i< xsqlvars.length; i++) {
                if (columnName.equals(xsqlvars[i].aliasname)) {
                    return ++i;
                }
            }
            // case-sensitively check column names
            for (int i = 0; i< xsqlvars.length; i++) {
                if (columnName.equals(xsqlvars[i].sqlname)) {
                    return ++i;
                }
            } 
        }
		  else {
            for (int i = 0; i< xsqlvars.length; i++) {
                if (columnName.equalsIgnoreCase(xsqlvars[i].aliasname)) {
                    return ++i;
                }
            }
            for (int i = 0; i< xsqlvars.length; i++) {
                if (columnName.equalsIgnoreCase(xsqlvars[i].sqlname)) {
                    return ++i;
                }
            }
        }        

        throw new FBSQLException(
                "Column name " + columnName + " not found in result set.",
                FBSQLException.SQL_STATE_INVALID_COLUMN); 
    }



    //--------------------------JDBC 2.0-----------------------------------

    //---------------------------------------------------------------------
    // Getters and Setters
    //---------------------------------------------------------------------

    /**
     * Gets the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a
     * <code>java.io.Reader</code> object.
     * @return a <code>java.io.Reader</code> object that contains the column
     * value; if the value is SQL <code>NULL</code>, the value returned is
     * <code>null</code> in the Java programming language.
     * @param columnIndex the first column is 1, the second is 2, ...
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Reader getCharacterStream(int columnIndex) throws  SQLException {
        return getField(columnIndex).getCharacterStream();
    }


    /**
     * Gets the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a
     * <code>java.io.Reader</code> object.
     *
     * @return a <code>java.io.Reader</code> object that contains the column
     * value; if the value is SQL <code>NULL</code>, the value returned is
     * <code>null</code> in the Java programming language.
     * @param columnName the name of the column
     * @return the value in the specified column as a <code>java.io.Reader</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Reader getCharacterStream(String columnName) throws  SQLException {
        return getField(columnName).getCharacterStream();
    }



    /**
     * Gets the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a
     * <code>java.math.BigDecimal</code> with full precision.
     *
     * @param columnName the column name
     * @return the column value (full precision);
     * if the value is SQL <code>NULL</code>, the value returned is
     * <code>null</code> in the Java programming language.
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     *
     */
    public BigDecimal getBigDecimal(String columnName) throws  SQLException {
        return getField(columnName).getBigDecimal();
    }


    //---------------------------------------------------------------------
    // Traversal/Positioning
    //---------------------------------------------------------------------

    /**
     * Indicates whether the cursor is before the first row in
     * this <code>ResultSet</code> object.
     *
     * @return <code>true</code> if the cursor is before the first row;
     * <code>false</code> if the cursor is at any other position or the
     * result set contains no rows
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean isBeforeFirst() throws  SQLException {
         return fbFetcher.isBeforeFirst();
    }


    /**
     * Indicates whether the cursor is after the last row in
     * this <code>ResultSet</code> object.
     *
     * @return <code>true</code> if the cursor is after the last row;
     * <code>false</code> if the cursor is at any other position or the
     * result set contains no rows
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean isAfterLast() throws  SQLException {
        return fbFetcher.isAfterLast();
    }


    /**
     * Indicates whether the cursor is on the first row of
     * this <code>ResultSet</code> object.
     *
     * @return <code>true</code> if the cursor is on the first row;
     * <code>false</code> otherwise
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean isFirst() throws  SQLException {
         return fbFetcher.isFirst();
    }


    /**
     * Indicates whether the cursor is on the last row of
     * this <code>ResultSet</code> object.
     * Note: Calling the method <code>isLast</code> may be expensive
     * because the JDBC driver
     * might need to fetch ahead one row in order to determine
     * whether the current row is the last row in the result set.
     *
     * @return <code>true</code> if the cursor is on the last row;
     * <code>false</code> otherwise
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean isLast() throws  SQLException {
       return fbFetcher.isLast();
    }


    /**
     * Moves the cursor to the front of
     * this <code>ResultSet</code> object, just before the
     * first row. This method has no effect if the result set contains no rows.
     *
     * @exception SQLException if a database access error
     * occurs or the result set type is <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void beforeFirst() throws  SQLException {
        checkCursorMove();
        fbFetcher.beforeFirst();
    }


    /**
     * Moves the cursor to the end of
     * this <code>ResultSet</code> object, just after the
     * last row. This method has no effect if the result set contains no rows.
     * @exception SQLException if a database access error
     * occurs or the result set type is <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void afterLast() throws  SQLException {
        checkCursorMove();
        fbFetcher.afterLast();
    }


    /**
     * Moves the cursor to the first row in
     * this <code>ResultSet</code> object.
     *
     * @return <code>true</code> if the cursor is on a valid row;
     * <code>false</code> if there are no rows in the result set
     * @exception SQLException if a database access error
     * occurs or the result set type is <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean first() throws  SQLException {
        checkCursorMove();
        return fbFetcher.first();
    }


    /**
     * Moves the cursor to the last row in
     * this <code>ResultSet</code> object.
     *
     * @return <code>true</code> if the cursor is on a valid row;
     * <code>false</code> if there are no rows in the result set
     * @exception SQLException if a database access error
     * occurs or the result set type is <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean last() throws  SQLException {
        checkCursorMove();
        return fbFetcher.last();
    }


    /**
     * Retrieves the current row number.  The first row is number 1, the
     * second number 2, and so on.
     *
     * @return the current row number; <code>0</code> if there is no current row
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getRow() throws  SQLException {
       return fbFetcher.getRowNum();
    }


    /**
     * Moves the cursor to the given row number in
     * this <code>ResultSet</code> object.
     *
     * <p>If the row number is positive, the cursor moves to
     * the given row number with respect to the
     * beginning of the result set.  The first row is row 1, the second
     * is row 2, and so on.
     *
     * <p>If the given row number is negative, the cursor moves to
     * an absolute row position with respect to
     * the end of the result set.  For example, calling the method
     * <code>absolute(-1)</code> positions the
     * cursor on the last row; calling the method <code>absolute(-2)</code>
     * moves the cursor to the next-to-last row, and so on.
     *
     * <p>An attempt to position the cursor beyond the first/last row in
     * the result set leaves the cursor before the first row or after
     * the last row.
     *
     * <p><B>Note:</B> Calling <code>absolute(1)</code> is the same
     * as calling <code>first()</code>. Calling <code>absolute(-1)</code>
     * is the same as calling <code>last()</code>.
     *
     * @return <code>true</code> if the cursor is on the result set;
     * <code>false</code> otherwise
     * @exception SQLException if a database access error
     * occurs, the row is <code>0</code>, or the result set type is
     * <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean absolute( int row ) throws  SQLException {
        checkCursorMove();
        return fbFetcher.absolute(row);
    }


    /**
     * Moves the cursor a relative number of rows, either positive or negative.
     * Attempting to move beyond the first/last row in the
     * result set positions the cursor before/after the
     * the first/last row. Calling <code>relative(0)</code> is valid, but does
     * not change the cursor position.
     *
     * <p>Note: Calling the method <code>relative(1)</code>
     * is different from calling the method <code>next()</code>
     * because is makes sense to call <code>next()</code> when there
     * is no current row,
     * for example, when the cursor is positioned before the first row
     * or after the last row of the result set.
     *
     * @return <code>true</code> if the cursor is on a row;
     * <code>false</code> otherwise
     * @exception SQLException if a database access error occurs,
     * there is no current row, or the result set type is
     * <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean relative( int rows ) throws  SQLException {
        checkCursorMove();
        return fbFetcher.relative(rows);
    }


    /**
     * Moves the cursor to the previous row in this
     * <code>ResultSet</code> object.
     *
     * <p><B>Note:</B> Calling the method <code>previous()</code> is not the same as
     * calling the method <code>relative(-1)</code> because it
     * makes sense to call</code>previous()</code> when there is no current row.
     *
     * @return <code>true</code> if the cursor is on a valid row;
     * <code>false</code> if it is off the result set
     * @exception SQLException if a database access error
     * occurs or the result set type is <code>TYPE_FORWARD_ONLY</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean previous() throws  SQLException {
        checkCursorMove();
        return fbFetcher.previous();
    }


    //---------------------------------------------------------------------
    // Properties
    //---------------------------------------------------------------------

    /**
     * Gives a hint as to the direction in which the rows in this
     * <code>ResultSet</code> object will be processed.
     * The initial value is determined by the
     * <code>Statement</code> object
     * that produced this <code>ResultSet</code> object.
     * The fetch direction may be changed at any time.
     *
     * @exception SQLException if a database access error occurs or
     * the result set type is <code>TYPE_FORWARD_ONLY</code> and the fetch
     * direction is not <code>FETCH_FORWARD</code>
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     * @see Statement#setFetchDirection
     */
    public void setFetchDirection(int direction) throws  SQLException {
         if (direction != ResultSet.FETCH_FORWARD)
            throw new FBDriverNotCapableException("Can't set fetch direction");
    }


    /**
     * Returns the fetch direction for this
     * <code>ResultSet</code> object.
     *
     * @return the current fetch direction for this <code>ResultSet</code> object
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getFetchDirection() throws  SQLException {
       return ResultSet.FETCH_FORWARD;
    }


    /**
     * Gives the JDBC driver a hint as to the number of rows that should
     * be fetched from the database when more rows are needed for this
     * <code>ResultSet</code> object.
     * If the fetch size specified is zero, the JDBC driver
     * ignores the value and is free to make its own best guess as to what
     * the fetch size should be.  The default value is set by the
     * <code>Statement</code> object
     * that created the result set.  The fetch size may be changed at any time.
     *
     * @param rows the number of rows to fetch
     * @exception SQLException if a database access error occurs or the
     * condition <code>0 <= rows <= this.getMaxRows()</code> is not satisfied
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void setFetchSize(int rows) throws  SQLException {
         if (rows < 0)
             throw new FBSQLException("Can't set negative fetch size.",
                     FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
         
         else if (maxRows > 0 && rows > maxRows)
             throw new FBSQLException("Can't set fetch size > maxRows.",
                     FBSQLException.SQL_STATE_INVALID_ARG_VALUE);
         else
        fetchSize = rows;
    }


    /**
     *
     * Returns the fetch size for this
     * <code>ResultSet</code> object.
     *
     * @return the current fetch size for this <code>ResultSet</code> object
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getFetchSize() throws  SQLException {
        return fetchSize;
    }


    /**
     * Returns the type of this <code>ResultSet</code> object.
     * The type is determined by the <code>Statement</code> object
     * that created the result set.
     *
     * @return <code>TYPE_FORWARD_ONLY</code>,
     * <code>TYPE_SCROLL_INSENSITIVE</code>,
     * or <code>TYPE_SCROLL_SENSITIVE</code>
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getType() throws  SQLException {
        return rsType;
    }

    /**
     * Returns the concurrency mode of this <code>ResultSet</code> object.
     * The concurrency used is determined by the
     * <code>Statement</code> object that created the result set.
     *
     * @return the concurrency type, either <code>CONCUR_READ_ONLY</code>
     * or <code>CONCUR_UPDATABLE</code>
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public int getConcurrency() throws  SQLException {
        return rsConcurrency;
    }


    //---------------------------------------------------------------------
    // Updates
    //---------------------------------------------------------------------

    /**
     * Indicates whether the current row has been updated.  The value returned
     * depends on whether or not the result set can detect updates.
     *
     * @return <code>true</code> if the row has been visibly updated
     * by the owner or another, and updates are detected
     * @exception SQLException if a database access error occurs
     *
     * @see DatabaseMetaData#updatesAreDetected
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean rowUpdated() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Indicates whether the current row has had an insertion.
     * The value returned depends on whether or not this
     * <code>ResultSet</code> object can detect visible inserts.
     *
     * @return <code>true</code> if a row has had an insertion
     * and insertions are detected; <code>false</code> otherwise
     * @exception SQLException if a database access error occurs
     *
     * @see DatabaseMetaData#insertsAreDetected
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean rowInserted() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Indicates whether a row has been deleted.  A deleted row may leave
     * a visible "hole" in a result set.  This method can be used to
     * detect holes in a result set.  The value returned depends on whether
     * or not this <code>ResultSet</code> object can detect deletions.
     *
     * @return <code>true</code> if a row was deleted and deletions are detected;
     * <code>false</code> otherwise
     * @exception SQLException if a database access error occurs
     *
     * @see DatabaseMetaData#deletesAreDetected
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public boolean rowDeleted() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Gives a nullable column a null value.
     *
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code>
     * or <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateNull(int columnIndex) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>boolean</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBoolean(int columnIndex, boolean x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>byte</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateByte(int columnIndex, byte x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>short</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateShort(int columnIndex, short x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>int</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateInt(int columnIndex, int x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>long</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateLong(int columnIndex, long x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>float</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateFloat(int columnIndex, float x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>double</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateDouble(int columnIndex, double x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.math.BigDecimal</code>
     * value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>String</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateString(int columnIndex, String x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>byte</code> array value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBytes(int columnIndex, byte x[]) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Date</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateDate(int columnIndex, Date x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Time</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateTime(int columnIndex, Time x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Timestamp</code>
     * value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateTimestamp(int columnIndex, Timestamp x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an ascii stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateAsciiStream(int columnIndex, InputStream x,
               int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a binary stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBinaryStream(int columnIndex, InputStream x,
                int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a character stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateCharacterStream(int columnIndex, Reader x,
                 int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>Object</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @param scale for <code>java.sql.Types.DECIMA</code>
     *  or <code>java.sql.Types.NUMERIC</code> types,
     *  this is the number of digits after the decimal point.  For all other
     *  types this value will be ignored.
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateObject(int columnIndex, Object x, int scale) 
        throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>Object</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateObject(int columnIndex, Object x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>null</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateNull(String columnName) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>boolean</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBoolean(String columnName, boolean x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>byte</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateByte(String columnName, byte x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>short</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateShort(String columnName, short x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>int</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateInt(String columnName, int x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>long</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateLong(String columnName, long x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>float </code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateFloat(String columnName, float x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>double</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateDouble(String columnName, double x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.BigDecimal</code>
     * value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBigDecimal(String columnName, BigDecimal x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>String</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateString(String columnName, String x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>boolean</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * JDBC 2.0
     *
     * Updates a column with a byte array value.
     *
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row, or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or <code>insertRow</code>
     * methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBytes(String columnName, byte x[]) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Date</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateDate(String columnName, Date x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Time</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateTime(String columnName, Time x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a <code>java.sql.Timestamp</code>
     * value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateTimestamp(String columnName, Timestamp x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an ascii stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateAsciiStream(String columnName, InputStream x,
               int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a binary stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateBinaryStream(String columnName, InputStream x,
                int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with a character stream value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param reader the new column value
     * @param length the length of the stream
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateCharacterStream(String columnName, Reader reader,
                 int length) throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>Object</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @param scale for <code>java.sql.Types.DECIMA</code>
     *  or <code>java.sql.Types.NUMERIC</code> types,
     *  this is the number of digits after the decimal point.  For all other
     *  types this value will be ignored.
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateObject(String columnName, Object x, int scale) 
        throws  SQLException 
    {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the designated column with an <code>Object</code> value.
     * The <code>updateXXX</code> methods are used to update column values in the
     * current row or the insert row.  The <code>updateXXX</code> methods do not
     * update the underlying database; instead the <code>updateRow</code> or
     * <code>insertRow</code> methods are called to update the database.
     *
     * @param columnName the name of the column
     * @param x the new column value
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateObject(String columnName, Object x) throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Inserts the contents of the insert row into this
     * <code>ResultSet</code> objaect and into the database.
     * The cursor must be on the insert row when this method is called.
     *
     * @exception SQLException if a database access error occurs,
     * if this method is called when the cursor is not on the insert row,
     * or if not all of non-nullable columns in
     * the insert row have been given a value
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void insertRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Updates the underlying database with the new contents of the
     * current row of this <code>ResultSet</code> object.
     * This method cannot be called when the cursor is on the insert row.
     *
     * @exception SQLException if a database access error occurs or
     * if this method is called when the cursor is on the insert row
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void updateRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Deletes the current row from this <code>ResultSet</code> object
     * and from the underlying database.  This method cannot be called when
     * the cursor is on the insert row.
     *
     * @exception SQLException if a database access error occurs
     * or if this method is called when the cursor is on the insert row
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void deleteRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Refreshes the current row with its most recent value in
     * the database.  This method cannot be called when
     * the cursor is on the insert row.
     *
     * <P>The <code>refreshRow</code> method provides a way for an
     * application to
     * explicitly tell the JDBC driver to refetch a row(s) from the
     * database.  An application may want to call <code>refreshRow</code> when
     * caching or prefetching is being done by the JDBC driver to
     * fetch the latest value of a row from the database.  The JDBC driver
     * may actually refresh multiple rows at once if the fetch size is
     * greater than one.
     *
     * <P> All values are refetched subject to the transaction isolation
     * level and cursor sensitivity.  If <code>refreshRow</code> is called after
     * calling an <code>updateXXX</code> method, but before calling
     * the method <code>updateRow</code>, then the
     * updates made to the row are lost.  Calling the method
     * <code>refreshRow</code> frequently will likely slow performance.
     *
     * @exception SQLException if a database access error
     * occurs or if this method is called when the cursor is on the insert row
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void refreshRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Cancels the updates made to the current row in this
     * <code>ResultSet</code> object.
     * This method may be called after calling an
     * <code>updateXXX</code> method(s) and before calling
     * the method <code>updateRow</code> to roll back
     * the updates made to a row.  If no updates have been made or
     * <code>updateRow</code> has already been called, this method has no
     * effect.
     *
     * @exception SQLException if a database access error
     * occurs or if this method is called when the cursor is on the insert row
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void cancelRowUpdates() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Moves the cursor to the insert row.  The current cursor position is
     * remembered while the cursor is positioned on the insert row.
     *
     * The insert row is a special row associated with an updatable
     * result set.  It is essentially a buffer where a new row may
     * be constructed by calling the <code>updateXXX</code> methods prior to
     * inserting the row into the result set.
     *
     * Only the <code>updateXXX</code>, <code>getXXX</code>,
     * and <code>insertRow</code> methods may be
     * called when the cursor is on the insert row.  All of the columns in
     * a result set must be given a value each time this method is
     * called before calling <code>insertRow</code>.
     * An <code>updateXXX</code> method must be called before a
     * <code>getXXX</code> method can be called on a column value.
     *
     * @exception SQLException if a database access error occurs
     * or the result set is not updatable
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void moveToInsertRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Moves the cursor to the remembered cursor position, usually the
     * current row.  This method has no effect if the cursor is not on
     * the insert row.
     *
     * @exception SQLException if a database access error occurs
     * or the result set is not updatable
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public void moveToCurrentRow() throws  SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * Returns the <code>Statement</code> object that produced this
     * <code>ResultSet</code> object.
     * If the result set was generated some other way, such as by a
     * <code>DatabaseMetaData</code> method, this method returns
     * <code>null</code>.
     *
     * @return the <code>Statment</code> object that produced
     * this <code>ResultSet</code> object or <code>null</code>
     * if the result set was produced some other way
     */
    public Statement getStatement() {
        return fbFetcher.getStatement();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as an <code>Object</code>
     * in the Java programming language.
     * This method uses the given <code>Map</code> object
     * for the custom mapping of the
     * SQL structured or distinct type that is being retrieved.
     *
     * @param i the first column is 1, the second is 2, ...
     * @param map a <code>java.util.Map</code> object that contains the mapping
     * from SQL type names to classes in the Java programming language
     * @return an <code>Object</code> in the Java programming language
     * representing the SQL value
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Object getObject(int i, Map map) throws  SQLException {
        return getField(i).getObject(map);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>Ref</code> object
     * in the Java programming language.
     *
     * @param i the first column is 1, the second is 2, ...
     * @return a <code>Ref</code> object representing an SQL <code>REF</code> value
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Ref getRef(int i) throws  SQLException {
        return getField(i).getRef();
    }



    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>Clob</code> object
     * in the Java programming language.
     *
     * @param i the first column is 1, the second is 2, ...
     * @return a <code>Clob</code> object representing the SQL <code>CLOB</code> value in
     *         the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Clob getClob(int i) throws  SQLException {
        return getField(i).getClob();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as an <code>Array</code> object
     * in the Java programming language.
     *
     * @param i the first column is 1, the second is 2, ...
     * @return an <code>Array</code> object representing the SQL <code>ARRAY</code> value in
     *         the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Array getArray(int i) throws  SQLException {
        return getField(i).getArray();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as an <code>Object</code>
     * in the Java programming language.
     * This method uses the specified <code>Map</code> object for
     * custom mapping if appropriate.
     *
     * @param columnName the name of the column from which to retrieve the value
     * @param map a <code>java.util.Map</code> object that contains the mapping
     * from SQL type names to classes in the Java programming language
     * @return an <code>Object</code> representing the SQL value in the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Object getObject(String columnName, Map map) throws  SQLException {
        return getField(columnName).getObject(map);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>Ref</code> object
     * in the Java programming language.
     *
     * @param columnName the column name
     * @return a <code>Ref</code> object representing the SQL <code>REF</code> value in
     *         the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Ref getRef(String columnName) throws  SQLException {
        return getField(columnName).getRef();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>Blob</code> object
     * in the Java programming language.
     *
     * @param columnName the name of the column from which to retrieve the value
     * @return a <code>Blob</code> object representing the SQL <code>BLOB</code> value in
     *         the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Blob getBlob(String columnName) throws  SQLException {
        return getField(columnName).getBlob();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>Clob</code> object
     * in the Java programming language.
     *
     * @param columnName the name of the column from which to retrieve the value
     * @return a <code>Clob</code> object representing the SQL <code>CLOB</code>
     * value in the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Clob getClob(String columnName) throws  SQLException {
        return getField(columnName).getClob();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as an <code>Array</code> object
     * in the Java programming language.
     *
     * @param columnName the name of the column from which to retrieve the value
     * @return an <code>Array</code> object representing the SQL <code>ARRAY</code> value in
     *         the specified column
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Array getArray(String columnName) throws  SQLException {
        return getField(columnName).getArray();
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Date</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the date if the underlying database does not store
     * timezone information.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the date
     * @return the column value as a <code>java.sql.Date</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Date getDate(int columnIndex, Calendar cal)
        throws  SQLException
    {
        return getField(columnIndex).getDate(cal);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Date</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the date if the underlying database does not store
     * timezone information.
     *
     * @param columnName the SQL name of the column from which to retrieve the value
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the date
     * @return the column value as a <code>java.sql.Date</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Date getDate(String columnName, Calendar cal) throws  SQLException {
        return getField(columnName).getDate(cal);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Time</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the time if the underlying database does not store
     * timezone information.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the time
     * @return the column value as a <code>java.sql.Time</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Time getTime(int columnIndex, Calendar cal)
        throws  SQLException
    {
        return getField(columnIndex).getTime(cal);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Time</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the time if the underlying database does not store
     * timezone information.
     *
     * @param columnName the SQL name of the column
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the time
     * @return the column value as a <code>java.sql.Time</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Time getTime(String columnName, Calendar cal) throws  SQLException {
       return getField(columnName).getTime(cal);
     }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Timestamp</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the timestamp if the underlying database does not store
     * timezone information.
     *
     * @param columnIndex the first column is 1, the second is 2, ...
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the timestamp
     * @return the column value as a <code>java.sql.Timestamp</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Timestamp getTimestamp(int columnIndex, Calendar cal)
        throws  SQLException
    {
        return getField(columnIndex).getTimestamp(cal);
    }


    /**
     * Returns the value of the designated column in the current row
     * of this <code>ResultSet</code> object as a <code>java.sql.Timestamp</code> object
     * in the Java programming language.
     * This method uses the given calendar to construct an appropriate millisecond
     * value for the timestamp if the underlying database does not store
     * timezone information.
     *
     * @param columnName the SQL name of the column
     * @param cal the <code>java.util.Calendar</code> object
     * to use in constructing the date
     * @return the column value as a <code>java.sql.Timestamp</code> object;
     * if the value is SQL <code>NULL</code>,
     * the value returned is <code>null</code> in the Java programming language
     * @exception SQLException if a database access error occurs
     * @since 1.2
     * @see <a href="package-summary.html#2.0 API">What Is in the JDBC
     *      2.0 API</a>
     */
    public Timestamp getTimestamp(String columnName, Calendar cal) throws  SQLException {
       return getField(columnName).getTimestamp(cal);
     }

    //jdbc 3 methods

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @return <description>
     * @exception java.sql.SQLException <description>
     */
    public URL getURL(int param1) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @return <description>
     * @exception java.sql.SQLException <description>
     */
    public URL getURL(String param1) throws SQLException {
        throw new FBDriverNotCapableException();
    }


    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateRef(int param1, Ref param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateRef(String param1, Ref param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateBlob(int param1, Blob param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateBlob(String param1, Blob param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateClob(int param1, Clob param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateClob(String param1, Clob param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateArray(int param1, Array param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    /**
     * <b>This operation is not supported</b>
     *
     * @param param1 <description>
     * @param param2 <description>
     * @exception java.sql.SQLException <description>
     */
    public void updateArray(String param1, Array param2) throws SQLException {
        throw new FBDriverNotCapableException();
    }

    //--------------------------------------------------------------------

     protected void addWarning(SQLWarning warning){
         if (firstWarning == null)
             firstWarning = warning;
         else{
             SQLWarning lastWarning = firstWarning;
             while (lastWarning.getNextWarning() != null){
                 lastWarning = lastWarning.getNextWarning();
             }
             lastWarning.setNextWarning(warning);
         }
     }
}
