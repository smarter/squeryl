/*******************************************************************************
 * Copyright 2010 Maxime Lévesque
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.squeryl.adapters

import org.squeryl.internals.{StatementWriter, DatabaseAdapter}
import org.squeryl.{Session, Table}
import org.squeryl.dsl.ast._
import java.sql.SQLException
import collection.Set
import collection.immutable.List
import collection.mutable.HashSet

class OracleAdapter extends DatabaseAdapter {

  override def intTypeDeclaration = "number"
  override def stringTypeDeclaration = "varchar2(255)"
  override def stringTypeDeclaration(length:Int) = "varchar2("+length+")"
  override def booleanTypeDeclaration = "number(1)"
  override def doubleTypeDeclaration = "double precision"
  override def longTypeDeclaration = "number"

  override def supportsAutoIncrementInColumnDeclaration: Boolean = false

  override def postCreateTable(s: Session, t: Table[_]) = {
    super.postCreateTable(s,t)
    val sw = new StatementWriter(false, this)
    sw.write("create sequence ", sequenceName(t), " start with 1 increment by 1 nomaxvalue")
    val st = s.connection.createStatement
    st.execute(sw.statement)
  }

  override def postDropTable(t: Table[_]) =
    execFailSafeExecute("drop sequence " + sequenceName(t), e=>e.getErrorCode == 2289)
  
  def sequenceName(t: Table[_]) = "s_" + t.name
  
  override def writeInsert[T](o: T, t: Table[T], sw: StatementWriter):Unit = {

    val o_ = o.asInstanceOf[AnyRef]

    val autoIncPK = t.posoMetaData.fieldsMetaData.find(fmd => fmd.isAutoIncremented)

    if(autoIncPK == None) {
      super.writeInsert(o, t, sw)
      return
    }

    val f = t.posoMetaData.fieldsMetaData.filter(fmd => fmd != autoIncPK.get)

    val colNames = List(autoIncPK.get) ::: f.toList
    val colVals = List(sequenceName(t) + ".nextval") ::: f.map(fmd => writeValue(o_, fmd, sw)).toList

    sw.write("insert into ");
    sw.write(t.name);
    sw.write(" (");
    sw.write(colNames.map(fmd => fmd.columnName).mkString(", "));
    sw.write(") values ");
    sw.write(colVals.mkString("(",",",")"));
  }

  override def writeConcatFunctionCall(fn: FunctionNode[_], sw: StatementWriter) =
    sw.writeNodesWithSeparator(fn.args, " || ", false)

  override def writeOuterJoin(oje: OuterJoinExpression, sw: StatementWriter) = {
    sw.write(oje.leftRightOrFull)
    sw.write(" outer join ")
    oje.queryableExpressionNode.write(sw)
    sw.write(" ")
    sw.write(oje.queryableExpressionNode.alias)
    sw.write(" on ")
    oje.matchExpression.write(sw)
  }

  override def writePaginatedQueryDeclaration(qen: QueryExpressionElements, sw: StatementWriter) = {} 

  override def writeQuery(qen: QueryExpressionElements, sw: StatementWriter) =
    if(qen.page == None)
      super.writeQuery(qen, sw)
    else {        
      sw.write("select sq____1.* from (")
      sw.nextLine
      sw.writeIndented {
        sw.write("select sq____0.*, rownum as rn____")
        sw.nextLine
        sw.write("from")
        sw.nextLine
        sw.writeIndented {
          sw.write("(")
          super.writeQuery(qen, sw)
          sw.write(") sq____0")
        }
      }
      sw.nextLine
      sw.write(") sq____1")
      sw.nextLine
      sw.write("where")
      sw.nextLine
      sw.writeIndented {
        sw.write("rn____ between ")
        val page = qen.page.get
        val beginOffset = page._1 + 1
        val endOffset = page._2 + beginOffset - 1
        sw.write(beginOffset.toString)
        sw.write(" and ")
        sw.write(endOffset.toString)
      }
    }

  override def isTableDoesNotExistException(e: SQLException) =
    e.getErrorCode == 942

  def legalOracleSuffixChars =
    OracleAdapter.legalOracleSuffixChars

  def paddingPossibilities(start: String, padLength: Int): Iterable[String] =
    if(padLength < 0)
      error("padLength must be positive, was given : " + padLength)
    else if(padLength == 0)
      Seq(start)
    else if(padLength == 1)
      legalOracleSuffixChars.map(start + _)
    else
      for(end <- legalOracleSuffixChars;
          pad <- paddingPossibilities(start, padLength - 1))
      yield pad + end

  class CouldNotShrinkIdentifierException extends RuntimeException

  def makeUniqueInScope(s: String, scope: Set[String], padLength: Int): String = {

    var prefix = s.substring(0, s.length - padLength)
    val possibilities = paddingPossibilities(prefix, padLength)

    for(p <- possibilities if !scope.contains(p))
      return p

    if(s.length == padLength) // at this point 's' is completely 'random like', not helpfull to add it in the error message
      throw new CouldNotShrinkIdentifierException

    makeUniqueInScope(s, scope, padLength + 1)
  }

  def makeUniqueInScope(s: String, scope: scala.collection.Set[String]): String =
    try {
      if(scope.contains(s))
        makeUniqueInScope(s, scope, 1)
      else
        s
    }
    catch {
      case e:CouldNotShrinkIdentifierException =>
        error("could not make a unique identifier with '" + s + "'")
    }

  def shrinkTo30AndPreserveUniquenessInScope(identifier: String, scope: HashSet[String]) =
    if(identifier.length <= 29)
      identifier
    else {
      val res = makeUniqueInScope(identifier.substring(0, 30), scope)
      scope.add(res)
      //println(identifier + "----->" + res)
      res
    }  

  override def writeSelectElementAlias(se: SelectElement, sw: StatementWriter) =
    sw.write(shrinkTo30AndPreserveUniquenessInScope(se.alias, sw.scope))

  override def foreingKeyConstraintName(foreingKeyTable: Table[_], idWithinSchema: Int) = {
    val name = super.foreingKeyConstraintName(foreingKeyTable, idWithinSchema)
    val r = shrinkTo30AndPreserveUniquenessInScope(name, foreingKeyTable.schema._namingScope)
    r
  }
}


object OracleAdapter {
  
  val legalOracleSuffixChars =
     "ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789".toCharArray.toList
}