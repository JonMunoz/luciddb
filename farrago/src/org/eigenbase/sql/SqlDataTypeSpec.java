/*
// $Id$
// Package org.eigenbase is a class library of database components.
// Copyright (C) 2002-2004 Disruptive Tech
// Copyright (C) 2003-2004 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.eigenbase.sql;

import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeFactory;
import org.eigenbase.reltype.RelDataTypeFactoryImpl;
import org.eigenbase.resource.EigenbaseResource;
import org.eigenbase.sql.parser.ParserPosition;
import org.eigenbase.sql.type.*;
import org.eigenbase.sql.util.SqlVisitor;
import org.eigenbase.util.Util;

import java.nio.charset.Charset;


/**
 * Represents a SQL data type specification in a parse tree.
 *
 * <p>todo: This should really be a subtype of {@link SqlCall}.
 *
 * <p>In its full glory, we will have to support complex type expressions like
 *
 * <blockquote><code>
 * ROW(
 *     NUMBER(5,2) NOT NULL AS foo,
 *     ROW(
 *         BOOLEAN AS b,
 *         MyUDT NOT NULL AS i
 *     ) AS rec
 * )</code></blockquote>
 *
 * <p>Currently it only supports simple datatypes, like char, varchar, double,
 * with optional precision and scale.
 *
 * @author Lee Schumacher
 * @since Jun 4, 2004
 * @version $Id$
 **/
public class SqlDataTypeSpec extends SqlNode
{
    //~ Instance fields -------------------------------------------------------

    private final SqlIdentifier typeName;
    private final int scale;
    private final int precision;
    private RelDataType type;
    private final String charSetName;

    //~ Constructors ----------------------------------------------------------

    public SqlDataTypeSpec(
        final SqlIdentifier typeName,
        int precision,
        int scale,
        String charSetName,
        ParserPosition pos)
    {
        super(pos);
        this.typeName = typeName;
        this.scale = scale;
        this.precision = precision;
        this.charSetName = charSetName;
    }

    //~ Methods ---------------------------------------------------------------

    public RelDataType getType()
    {
        return type;
    }

    public SqlIdentifier getTypeName()
    {
        return typeName;
    }

    public int getScale()
    {
        return scale;
    }

    public int getPrecision()
    {
        return precision;
    }

    public String getCharSetName()
    {
        return charSetName;
    }

    /**
     * Writes a SQL representation of this node to a writer.
     *
     * <p>The <code>leftPrec</code> and <code>rightPrec</code> parameters
     * give us enough context to decide whether we need to enclose the
     * expression in parentheses. For example, we need parentheses around
     * "2 + 3" if preceded by "5 *". This is because the precedence of the "*"
     * operator is greater than the precedence of the "+" operator.
     *
     * <p>The algorithm handles left- and right-associative operators by giving
     * them slightly different left- and right-precedence.
     *
     * <p>If {@link SqlWriter#alwaysUseParentheses} is true, we use parentheses
     * even when they are not required by the precedence rules.
     *
     * <p>For the details of this algorithm, see {@link SqlCall#unparse}.
     *
     * @param writer Target writer
     * @param leftPrec The precedence of the {@link SqlNode} immediately
     *   preceding this node in a depth-first scan of the parse tree
     * @param rightPrec The precedence of the {@link SqlNode} immediately
     *   following this node in a depth-first scan of the parse tree
     */
    public void unparse(
        SqlWriter writer,
        int leftPrec,
        int rightPrec)
    {
        String name = typeName.getSimple();
        if (SqlTypeName.containsName(name)) {
            //we have a built in data type
            writer.print(name);

            if (precision > 0) {
                writer.print("(" + precision);
                if (scale > 0) {
                    writer.print(", " + scale);
                }
                writer.print(")");
            }

            if (charSetName != null) {
                writer.print(" CHARACTER SET " + charSetName);
            }
        } else {
            // else we have a user defined type
            typeName.unparse(writer, leftPrec, rightPrec);
        }
    }

    public void validate(SqlValidator validator, SqlValidator.Scope scope)
    {
        validator.validateDataType(this);
    }

    public void accept(SqlVisitor visitor)
    {
        visitor.visit(this);
    }

    public boolean equalsDeep(SqlNode node)
    {
        if (node instanceof SqlDataTypeSpec) {
            SqlDataTypeSpec that = (SqlDataTypeSpec) node;
            return this.typeName.equalsDeep(that.typeName) &&
                this.precision == that.precision &&
                this.scale == that.scale &&
                Util.equal(this.charSetName, that.charSetName);
        }
        return false;
    }

    /**
     * Throws an error if the type is not built-in.
     */
    public RelDataType deriveType(SqlValidator validator)
    {
        String name = typeName.getSimple();

        //for now we only support builtin datatypes
        if (!SqlTypeName.containsName(name)) {
            throw validator.newValidationError(this,
                EigenbaseResource.instance().newUnknownDatatypeName(name));
        }

        SqlTypeName sqlTypeName = SqlTypeName.get(name);
        RelDataTypeFactory typeFactory = validator.typeFactory;

        // TODO jvs 13-Dec-2004:  these assertions should be real
        // validation errors instead; need to share code with DDL
        if ((precision > 0) && (scale > 0)) {
            assert(sqlTypeName.allowsPrecScale(true, true));
            type = typeFactory.createSqlType(sqlTypeName, precision, scale);
        } else if (precision > 0) {
            assert(sqlTypeName.allowsPrecNoScale());
            type = typeFactory.createSqlType(sqlTypeName, precision);
        } else {
            assert(sqlTypeName.allowsNoPrecNoScale());
            type = typeFactory.createSqlType(sqlTypeName);
        }

        if (SqlTypeUtil.inCharFamily(type)) {
            // Applying Syntax rule 10 from SQL:99 spec section 6.22 "If TD is a
            // fixed-length, variable-length or large object character string,
            // then the collating sequence of the result of the <cast
            // specification> is the default collating sequence for the
            // character repertoire of TD and the result of the <cast
            // specification> has the Coercible coercibility characteristic."
            SqlCollation collation =
                new SqlCollation(SqlCollation.Coercibility.Coercible);

            Charset charset;
            if (null == charSetName) {
                charset = Util.getDefaultCharset();
            } else {
                charset = Charset.forName(charSetName);
            }
            type =
                typeFactory.createTypeWithCharsetAndCollation(type, charset,
                    collation);
        }
        return type;
    }
}


// End SqlDataTypeSpec.java