package tech.kzen.sample.plugin.value;

import tech.kzen.lib.common.exec.data.type.DataContract;
import tech.kzen.lib.common.exec.data.type.DataField;
import tech.kzen.lib.common.exec.data.type.DataType;
import tech.kzen.lib.common.exec.data.type.FieldId;
import tech.kzen.lib.common.exec.data.type.ScalarKind;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.lib.common.exec.data.value.LiteralDataValues;
import tech.kzen.lib.common.exec.data.value.RecordLiteral;

import java.util.List;
import java.util.Map;


/**
 * Flat typed records the Java way: a static contract of scalar columns declared once, and each row a column →
 * value map lifted as a literal under it (an integer as any {@link Number}, a floating value as a double, text as
 * a string, a nullable column absent as null). Readers and Workers alike declare their shape through this, so
 * the host shows the columns before a run and validates every row against them.
 */
public final class LiteralRecords {
    public static final DataType.Scalar integer = new DataType.Scalar(new ScalarKind.Integer(64, true), false);
    public static final DataType.Scalar integerOrNull = new DataType.Scalar(new ScalarKind.Integer(64, true), true);
    public static final DataType.Scalar integer32 = new DataType.Scalar(new ScalarKind.Integer(32, true), false);
    public static final DataType.Scalar integer32OrNull = new DataType.Scalar(new ScalarKind.Integer(32, true), true);
    public static final DataType.Scalar floating = new DataType.Scalar(new ScalarKind.Floating(64), false);
    public static final DataType.Scalar floatingOrNull = new DataType.Scalar(new ScalarKind.Floating(64), true);
    public static final DataType.Scalar text = new DataType.Scalar(ScalarKind.Text.INSTANCE, false);
    public static final DataType.Scalar textOrNull = new DataType.Scalar(ScalarKind.Text.INSTANCE, true);
    public static final DataType.Scalar bool = new DataType.Scalar(ScalarKind.Boolean.INSTANCE, false);


    private LiteralRecords() {}


    public static DataField field(String name, DataType.Scalar type) {
        return new DataField(new FieldId(name, 0), type, false);
    }


    public static DataContract contract(List<DataField> fields) {
        return new DataContract(new DataType.Record(fields, false));
    }


    /** The record's column names in declaration order. */
    public static List<String> columns(DataContract contract) {
        return ((DataType.Record) contract.getStructural()).getFields().stream()
                .map(field -> field.getId().getName())
                .toList();
    }


    public static DataValue row(DataContract contract, Map<String, Object> columns) {
        return LiteralDataValues.INSTANCE.lift(RecordLiteral.of(columns), contract);
    }
}
