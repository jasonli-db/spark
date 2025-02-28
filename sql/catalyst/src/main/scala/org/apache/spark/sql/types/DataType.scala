/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.types

import java.util.Locale

import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.annotation.{JsonDeserialize, JsonSerialize}
import org.json4s._
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.SparkThrowable
import org.apache.spark.annotation.Stable
import org.apache.spark.sql.catalyst.analysis.Resolver
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.catalyst.types.DataTypeUtils
import org.apache.spark.sql.catalyst.util.DataTypeJsonUtils.{DataTypeJsonDeserializer, DataTypeJsonSerializer}
import org.apache.spark.sql.catalyst.util.StringConcat
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.types.DayTimeIntervalType._
import org.apache.spark.sql.types.YearMonthIntervalType._
import org.apache.spark.util.Utils

/**
 * The base type of all Spark SQL data types.
 *
 * @since 1.3.0
 */

@Stable
@JsonSerialize(using = classOf[DataTypeJsonSerializer])
@JsonDeserialize(using = classOf[DataTypeJsonDeserializer])
abstract class DataType extends AbstractDataType {
  /**
   * The default size of a value of this data type, used internally for size estimation.
   */
  def defaultSize: Int

  /** Name of the type used in JSON serialization. */
  def typeName: String = {
    this.getClass.getSimpleName
      .stripSuffix("$").stripSuffix("Type").stripSuffix("UDT")
      .toLowerCase(Locale.ROOT)
  }

  private[sql] def jsonValue: JValue = typeName

  /** The compact JSON representation of this data type. */
  def json: String = compact(render(jsonValue))

  /** The pretty (i.e. indented) JSON representation of this data type. */
  def prettyJson: String = pretty(render(jsonValue))

  /** Readable string representation for the type. */
  def simpleString: String = typeName

  /** String representation for the type saved in external catalogs. */
  def catalogString: String = simpleString

  /** Readable string representation for the type with truncation */
  private[sql] def simpleString(maxNumberFields: Int): String = simpleString

  def sql: String = simpleString.toUpperCase(Locale.ROOT)

  /**
   * Returns the same data type but set all nullability fields are true
   * (`StructField.nullable`, `ArrayType.containsNull`, and `MapType.valueContainsNull`).
   */
  private[spark] def asNullable: DataType

  /**
   * Returns true if any `DataType` of this DataType tree satisfies the given function `f`.
   */
  private[spark] def existsRecursively(f: (DataType) => Boolean): Boolean = f(this)

  override private[sql] def defaultConcreteType: DataType = this

  override private[sql] def acceptsType(other: DataType): Boolean =
    DataTypeUtils.sameType(this, other)
}


/**
 * @since 1.3.0
 */
@Stable
object DataType {

  private val FIXED_DECIMAL = """decimal\(\s*(\d+)\s*,\s*(\-?\d+)\s*\)""".r
  private val CHAR_TYPE = """char\(\s*(\d+)\s*\)""".r
  private val VARCHAR_TYPE = """varchar\(\s*(\d+)\s*\)""".r

  def fromDDL(ddl: String): DataType = {
    parseTypeWithFallback(
      ddl,
      CatalystSqlParser.parseDataType,
      fallbackParser = str => CatalystSqlParser.parseTableSchema(str))
  }

  /**
   * Parses data type from a string with schema. It calls `parser` for `schema`.
   * If it fails, calls `fallbackParser`. If the fallback function fails too, combines error message
   * from `parser` and `fallbackParser`.
   *
   * @param schema The schema string to parse by `parser` or `fallbackParser`.
   * @param parser The function that should be invoke firstly.
   * @param fallbackParser The function that is called when `parser` fails.
   * @return The data type parsed from the `schema` schema.
   */
  def parseTypeWithFallback(
      schema: String,
      parser: String => DataType,
      fallbackParser: String => DataType): DataType = {
    try {
      parser(schema)
    } catch {
      case NonFatal(e) =>
        try {
          fallbackParser(schema)
        } catch {
          case NonFatal(_) =>
            if (e.isInstanceOf[SparkThrowable]) {
              throw e
            }
            throw QueryCompilationErrors.schemaFailToParseError(schema, e)
        }
    }
  }

  def fromJson(json: String): DataType = parseDataType(parse(json))

  private val otherTypes = {
    Seq(NullType, DateType, TimestampType, BinaryType, IntegerType, BooleanType, LongType,
      DoubleType, FloatType, ShortType, ByteType, StringType, CalendarIntervalType,
      DayTimeIntervalType(DAY),
      DayTimeIntervalType(DAY, HOUR),
      DayTimeIntervalType(DAY, MINUTE),
      DayTimeIntervalType(DAY, SECOND),
      DayTimeIntervalType(HOUR),
      DayTimeIntervalType(HOUR, MINUTE),
      DayTimeIntervalType(HOUR, SECOND),
      DayTimeIntervalType(MINUTE),
      DayTimeIntervalType(MINUTE, SECOND),
      DayTimeIntervalType(SECOND),
      YearMonthIntervalType(YEAR),
      YearMonthIntervalType(MONTH),
      YearMonthIntervalType(YEAR, MONTH),
      TimestampNTZType)
      .map(t => t.typeName -> t).toMap
  }

  /** Given the string representation of a type, return its DataType */
  private def nameToType(name: String): DataType = {
    name match {
      case "decimal" => DecimalType.USER_DEFAULT
      case FIXED_DECIMAL(precision, scale) => DecimalType(precision.toInt, scale.toInt)
      case CHAR_TYPE(length) => CharType(length.toInt)
      case VARCHAR_TYPE(length) => VarcharType(length.toInt)
      // For backwards compatibility, previously the type name of NullType is "null"
      case "null" => NullType
      case "timestamp_ltz" => TimestampType
      case other => otherTypes.getOrElse(
        other,
        throw new IllegalArgumentException(
          s"Failed to convert the JSON string '$name' to a data type."))
    }
  }

  private object JSortedObject {
    def unapplySeq(value: JValue): Option[List[(String, JValue)]] = value match {
      case JObject(seq) => Some(seq.sortBy(_._1))
      case _ => None
    }
  }

  // NOTE: Map fields must be sorted in alphabetical order to keep consistent with the Python side.
  private[sql] def parseDataType(json: JValue): DataType = json match {
    case JString(name) =>
      nameToType(name)

    case JSortedObject(
    ("containsNull", JBool(n)),
    ("elementType", t: JValue),
    ("type", JString("array"))) =>
      ArrayType(parseDataType(t), n)

    case JSortedObject(
    ("keyType", k: JValue),
    ("type", JString("map")),
    ("valueContainsNull", JBool(n)),
    ("valueType", v: JValue)) =>
      MapType(parseDataType(k), parseDataType(v), n)

    case JSortedObject(
    ("fields", JArray(fields)),
    ("type", JString("struct"))) =>
      StructType(fields.map(parseStructField))

    // Scala/Java UDT
    case JSortedObject(
    ("class", JString(udtClass)),
    ("pyClass", _),
    ("sqlType", _),
    ("type", JString("udt"))) =>
      Utils.classForName[UserDefinedType[_]](udtClass).getConstructor().newInstance()

    // Python UDT
    case JSortedObject(
    ("pyClass", JString(pyClass)),
    ("serializedClass", JString(serialized)),
    ("sqlType", v: JValue),
    ("type", JString("udt"))) =>
        new PythonUserDefinedType(parseDataType(v), pyClass, serialized)

    case other =>
      throw new IllegalArgumentException(
        s"Failed to convert the JSON string '${compact(render(other))}' to a data type.")
  }

  private def parseStructField(json: JValue): StructField = json match {
    case JSortedObject(
    ("metadata", metadata: JObject),
    ("name", JString(name)),
    ("nullable", JBool(nullable)),
    ("type", dataType: JValue)) =>
      StructField(name, parseDataType(dataType), nullable, Metadata.fromJObject(metadata))
    // Support reading schema when 'metadata' is missing.
    case JSortedObject(
    ("name", JString(name)),
    ("nullable", JBool(nullable)),
    ("type", dataType: JValue)) =>
      StructField(name, parseDataType(dataType), nullable)
    case other =>
      throw new IllegalArgumentException(
        s"Failed to convert the JSON string '${compact(render(other))}' to a field.")
  }

  protected[types] def buildFormattedString(
      dataType: DataType,
      prefix: String,
      stringConcat: StringConcat,
      maxDepth: Int): Unit = {
    dataType match {
      case array: ArrayType =>
        array.buildFormattedString(prefix, stringConcat, maxDepth - 1)
      case struct: StructType =>
        struct.buildFormattedString(prefix, stringConcat, maxDepth - 1)
      case map: MapType =>
        map.buildFormattedString(prefix, stringConcat, maxDepth - 1)
      case _ =>
    }
  }

  /**
   * Compares two types, ignoring compatible nullability of ArrayType, MapType, StructType.
   *
   * Compatible nullability is defined as follows:
   *   - If `from` and `to` are ArrayTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.containsNull` is true, or both of `from.containsNull` and
   *   `to.containsNull` are false.
   *   - If `from` and `to` are MapTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.valueContainsNull` is true, or both of `from.valueContainsNull` and
   *   `to.valueContainsNull` are false.
   *   - If `from` and `to` are StructTypes, `from` has a compatible nullability with `to`
   *   if and only if for all every pair of fields, `to.nullable` is true, or both
   *   of `fromField.nullable` and `toField.nullable` are false.
   */
  private[sql] def equalsIgnoreCompatibleNullability(from: DataType, to: DataType): Boolean = {
    equalsIgnoreCompatibleNullability(from, to, ignoreName = false)
  }

  /**
   * Compares two types, ignoring compatible nullability of ArrayType, MapType, StructType, and
   * also the field name. It compares based on the position.
   *
   * Compatible nullability is defined as follows:
   *   - If `from` and `to` are ArrayTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.containsNull` is true, or both of `from.containsNull` and
   *   `to.containsNull` are false.
   *   - If `from` and `to` are MapTypes, `from` has a compatible nullability with `to`
   *   if and only if `to.valueContainsNull` is true, or both of `from.valueContainsNull` and
   *   `to.valueContainsNull` are false.
   *   - If `from` and `to` are StructTypes, `from` has a compatible nullability with `to`
   *   if and only if for all every pair of fields, `to.nullable` is true, or both
   *   of `fromField.nullable` and `toField.nullable` are false.
   */
  private[sql] def equalsIgnoreNameAndCompatibleNullability(
      from: DataType,
      to: DataType): Boolean = {
    equalsIgnoreCompatibleNullability(from, to, ignoreName = true)
  }

  private def equalsIgnoreCompatibleNullability(
      from: DataType,
      to: DataType,
      ignoreName: Boolean = false): Boolean = {
    (from, to) match {
      case (ArrayType(fromElement, fn), ArrayType(toElement, tn)) =>
        (tn || !fn) && equalsIgnoreCompatibleNullability(fromElement, toElement, ignoreName)

      case (MapType(fromKey, fromValue, fn), MapType(toKey, toValue, tn)) =>
        (tn || !fn) &&
          equalsIgnoreCompatibleNullability(fromKey, toKey, ignoreName) &&
          equalsIgnoreCompatibleNullability(fromValue, toValue, ignoreName)

      case (StructType(fromFields), StructType(toFields)) =>
        fromFields.length == toFields.length &&
          fromFields.zip(toFields).forall { case (fromField, toField) =>
            (ignoreName || fromField.name == toField.name) &&
              (toField.nullable || !fromField.nullable) &&
              equalsIgnoreCompatibleNullability(fromField.dataType, toField.dataType, ignoreName)
          }

      case (fromDataType, toDataType) => fromDataType == toDataType
    }
  }

  /**
   * Returns true if the two data types share the same "shape", i.e. the types
   * are the same, but the field names don't need to be the same.
   *
   * @param ignoreNullability whether to ignore nullability when comparing the types
   */
  def equalsStructurally(
      from: DataType,
      to: DataType,
      ignoreNullability: Boolean = false): Boolean = {
    (from, to) match {
      case (left: ArrayType, right: ArrayType) =>
        equalsStructurally(left.elementType, right.elementType, ignoreNullability) &&
          (ignoreNullability || left.containsNull == right.containsNull)

      case (left: MapType, right: MapType) =>
        equalsStructurally(left.keyType, right.keyType, ignoreNullability) &&
          equalsStructurally(left.valueType, right.valueType, ignoreNullability) &&
          (ignoreNullability || left.valueContainsNull == right.valueContainsNull)

      case (StructType(fromFields), StructType(toFields)) =>
        fromFields.length == toFields.length &&
          fromFields.zip(toFields)
            .forall { case (l, r) =>
              equalsStructurally(l.dataType, r.dataType, ignoreNullability) &&
                (ignoreNullability || l.nullable == r.nullable)
            }

      case (fromDataType, toDataType) => fromDataType == toDataType
    }
  }

  /**
   * Returns true if the two data types have the same field names in order recursively.
   */
  def equalsStructurallyByName(
      from: DataType,
      to: DataType,
      resolver: Resolver): Boolean = {
    (from, to) match {
      case (left: ArrayType, right: ArrayType) =>
        equalsStructurallyByName(left.elementType, right.elementType, resolver)

      case (left: MapType, right: MapType) =>
        equalsStructurallyByName(left.keyType, right.keyType, resolver) &&
          equalsStructurallyByName(left.valueType, right.valueType, resolver)

      case (StructType(fromFields), StructType(toFields)) =>
        fromFields.length == toFields.length &&
          fromFields.zip(toFields)
            .forall { case (l, r) =>
              resolver(l.name, r.name) && equalsStructurallyByName(l.dataType, r.dataType, resolver)
            }

      case _ => true
    }
  }
}
