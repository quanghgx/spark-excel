/** Copyright 2016 - 2021 Martin Mauch (@nightscape)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package com.crealytics.spark.v2.excel

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.spark.sql.catalyst.analysis.TypeCoercion
import org.apache.spark.sql.catalyst.expressions.ExprUtils
import org.apache.spark.sql.catalyst.util.LegacyDateFormats.FAST_DATE_FORMAT
import org.apache.spark.sql.catalyst.util.TimestampFormatter
import org.apache.spark.sql.types._

import java.util.Locale
import scala.util.control.Exception.allCatch

class ExcelInferSchema(val options: ExcelOptions) extends Serializable {

  private val timestampParser = TimestampFormatter(
    options.timestampFormat,
    options.zoneId,
    options.locale,
    legacyFormat = FAST_DATE_FORMAT,
    isParsing = true
  )

  /** Similar to the JSON schema inference
    *     1. Infer type of each row
    *     2. Merge row types to find common type
    *     3. Replace any null types with string type
    */
  def infer(tokens: Seq[Vector[Cell]], header: Vector[String]): StructType = {
    val fields =
      if (options.inferSchema) {
        val startType: Vector[DataType] = Vector.fill[DataType](header.length)(NullType)
        val rootTypes: Vector[DataType] = tokens.aggregate(startType)(inferRowType, mergeRowTypes)

        toStructFields(rootTypes, header)
      } else {
        /* By default fields are assumed to be StringType*/
        header.map(fieldName => StructField(fieldName, StringType, nullable = true))
      }

    StructType(fields)
  }

  private def toStructFields(
      fieldTypes: Vector[DataType],
      header: Vector[String]
  ): Vector[StructField] = {
    header.zip(fieldTypes).map { case (thisHeader, rootType) =>
      val dType = rootType match {
        case _: NullType => StringType
        case other       => other
      }
      StructField(thisHeader, dType, nullable = true)
    }
  }

  private def inferRowType(rowSoFar: Vector[DataType], next: Vector[Cell]): Vector[DataType] =
    Range(0, rowSoFar.length).map(i =>
      if (i < next.length) inferField(rowSoFar(i), next(i))
      else compatibleType(rowSoFar(i), NullType).getOrElse(StringType)
    ).to[Vector]

  private def mergeRowTypes(first: Vector[DataType], second: Vector[DataType]): Vector[DataType] = {
    first.zipAll(second, NullType, NullType).map { case (a, b) =>
      compatibleType(a, b).getOrElse(NullType)
    }
  }

  /** Infer type of string field. Given known type Double, and a string "1",
    * there is no point checking if it is an Int, as the final type must be
    * Double or higher.
    */
  private def inferField(typeSoFar: DataType, field: Cell): DataType = {
    val typeElemInfer = field.getCellType match {
      case CellType.FORMULA => field.getCachedFormulaResultType match {
          case CellType.STRING  => StringType
          case CellType.NUMERIC => DoubleType
          case _                => NullType
        }
      case CellType.BLANK | CellType.ERROR | CellType._NONE => NullType
      case CellType.BOOLEAN                                 => BooleanType
      case CellType.NUMERIC =>
        if (DateUtil.isCellDateFormatted(field)) TimestampType else DoubleType
      case CellType.STRING => {
        val v = field.getStringCellValue
        if (v == options.nullValue) NullType
        else {
          typeSoFar match {
            case NullType       => tryParseInteger(v)
            case IntegerType    => tryParseInteger(v)
            case LongType       => tryParseLong(v)
            case _: DecimalType => tryParseDecimal(v)
            case DoubleType     => tryParseDouble(v)
            case TimestampType  => tryParseTimestamp(v)
            case BooleanType    => tryParseBoolean(v)
            case StringType     => StringType
            case other: DataType =>
              throw new UnsupportedOperationException(s"Unexpected data type $other")
          }
        }
      }
    }

    compatibleType(typeSoFar, typeElemInfer).getOrElse(StringType)
  }

  /* Special handling the default locale for backward compatibility*/
  private val decimalParser =
    if (options.locale == Locale.US) { s: String => new java.math.BigDecimal(s) }
    else { ExprUtils.getDecimalParser(options.locale) }

  private def isInfOrNan(field: String): Boolean = {
    field == options.nanValue || field == options.negativeInf || field == options.positiveInf
  }

  private def tryParseInteger(field: String): DataType =
    if ((allCatch opt field.toInt).isDefined) { IntegerType }
    else { tryParseLong(field) }

  private def tryParseLong(field: String): DataType =
    if ((allCatch opt field.toLong).isDefined) { LongType }
    else { tryParseDecimal(field) }

  private def tryParseDecimal(field: String): DataType = {
    val decimalTry = allCatch opt {
      /* The conversion can fail when the `field` is not a form of number.*/
      val bigDecimal = decimalParser(field)

      /** Because many other formats do not support decimal, it reduces the
        * cases for decimals by disallowing values having scale (eg. `1.1`).
        */
      if (bigDecimal.scale <= 0) {

        /** `DecimalType` conversion can fail when
          *   1. The precision is bigger than 38.
          *   2. scale is bigger than precision.
          */
        DecimalType(bigDecimal.precision, bigDecimal.scale)
      } else { tryParseDouble(field) }
    }
    decimalTry.getOrElse(tryParseDouble(field))
  }

  private def tryParseDouble(field: String): DataType =
    if ((allCatch opt field.toDouble).isDefined || isInfOrNan(field)) { DoubleType }
    else { tryParseTimestamp(field) }

  /* This case infers a custom `dataFormat` is set.*/
  private def tryParseTimestamp(field: String): DataType =
    if ((allCatch opt timestampParser.parse(field)).isDefined) { TimestampType }
    else { tryParseBoolean(field) }

  private def tryParseBoolean(field: String): DataType =
    if ((allCatch opt field.toBoolean).isDefined) { BooleanType }
    else { StringType }

  /** Returns the common data type given two input data types so that the return
    * type is compatible with both input data types.
    */
  private def compatibleType(t1: DataType, t2: DataType): Option[DataType] = {
    TypeCoercion.findTightestCommonType(t1, t2).orElse(findCompatibleTypeForExcel(t1, t2))
  }

  /** The following pattern matching represents additional type promotion rules
    * that are Excel specific.
    */
  private val findCompatibleTypeForExcel: (DataType, DataType) => Option[DataType] = {
    case (StringType, _) => Some(StringType)
    case (_, StringType) => Some(StringType)

    /** Double support larger range than fixed decimal, DecimalType.Maximum
      * should be enough in most case, also have better precision.
      */
    case (DoubleType, _: DecimalType) | (_: DecimalType, DoubleType) => Some(DoubleType)

    case (t1: DecimalType, t2: DecimalType) =>
      val scale = math.max(t1.scale, t2.scale)
      val range = math.max(t1.precision - t1.scale, t2.precision - t2.scale)
      if (range + scale > 38) {
        /* DecimalType can't support precision > 38*/
        Some(DoubleType)
      } else { Some(DecimalType(range + scale, scale)) }
    case _ => None
  }
}
