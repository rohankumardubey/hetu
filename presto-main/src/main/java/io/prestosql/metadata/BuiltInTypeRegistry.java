/*
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
package io.prestosql.metadata;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.prestosql.spi.type.ArrayType;
import io.prestosql.spi.type.CharParametricType;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.DecimalParametricType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.LikePatternType;
import io.prestosql.spi.type.MapType;
import io.prestosql.spi.type.ParametricType;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.RowType.Field;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeNotFoundException;
import io.prestosql.spi.type.TypeParameter;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.spi.type.TypeSignatureParameter;
import io.prestosql.spi.type.UnknownType;
import io.prestosql.spi.type.VarcharParametricType;
import io.prestosql.spi.type.VarcharType;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.type.CodePointsType;
import io.prestosql.type.ColorType;
import io.prestosql.type.JoniRegexpType;
import io.prestosql.type.JsonPathType;
import io.prestosql.type.Re2JRegexpType;
import io.prestosql.type.setdigest.SetDigestType;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static io.prestosql.spi.type.ArrayParametricType.ARRAY;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.CharType.createCharType;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.HyperLogLogType.HYPER_LOG_LOG;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.LikePatternType.LIKE_PATTERN;
import static io.prestosql.spi.type.MapParametricType.MAP;
import static io.prestosql.spi.type.P4HyperLogLogType.P4_HYPER_LOG_LOG;
import static io.prestosql.spi.type.QuantileDigestParametricType.QDIGEST;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.RowParametricType.ROW;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimeType.TIME;
import static io.prestosql.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static io.prestosql.spi.type.UnknownType.UNKNOWN;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.type.CodePointsType.CODE_POINTS;
import static io.prestosql.type.ColorType.COLOR;
import static io.prestosql.type.FunctionParametricType.FUNCTION;
import static io.prestosql.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static io.prestosql.type.IntervalYearMonthType.INTERVAL_YEAR_MONTH;
import static io.prestosql.type.IpAddressType.IPADDRESS;
import static io.prestosql.type.JoniRegexpType.JONI_REGEXP;
import static io.prestosql.type.JsonPathType.JSON_PATH;
import static io.prestosql.type.JsonType.JSON;
import static io.prestosql.type.Re2JRegexpType.RE2J_REGEXP;
import static io.prestosql.type.UuidType.UUID;
import static io.prestosql.type.setdigest.SetDigestType.SET_DIGEST;
import static java.util.Objects.requireNonNull;

@ThreadSafe
final class BuiltInTypeRegistry
{
    private final ConcurrentMap<TypeSignature, Type> types = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ParametricType> parametricTypes = new ConcurrentHashMap<>();
    private final FeaturesConfig featuresConfig;
    private final FunctionAndTypeManager functionAndTypeManager;

    private final Cache<TypeSignature, Type> parametricTypeCache;

    @Inject
    public BuiltInTypeRegistry(Set<Type> types, FeaturesConfig featureConfig, FunctionAndTypeManager functionAndTypeManager)
    {
        requireNonNull(types, "types is null");
        this.featuresConfig = requireNonNull(featureConfig, "featuresConfig is null");
        // Manually register UNKNOWN type without a verifyTypeClass call since it is a special type that can not be used by functions
        this.types.put(UNKNOWN.getTypeSignature(), UNKNOWN);
        this.functionAndTypeManager = requireNonNull(functionAndTypeManager, "functionAndTypeManager is null");

        // always add the built-in types; Presto will not function without these
        addType(BOOLEAN);
        addType(BIGINT);
        addType(INTEGER);
        addType("int", INTEGER);
        addType(SMALLINT);
        addType(TINYINT);
        addType(DOUBLE);
        addType(REAL);
        addType("float", REAL);
        addType(VARBINARY);
        addType(DATE);
        addType(TIME);
        addType(TIME_WITH_TIME_ZONE);
        addType(TIMESTAMP);
        addType(TIMESTAMP_WITH_TIME_ZONE);
        addType(INTERVAL_YEAR_MONTH);
        addType(INTERVAL_DAY_TIME);
        addType(HYPER_LOG_LOG);
        addType(SET_DIGEST);
        addType(P4_HYPER_LOG_LOG);
        addType(JONI_REGEXP);
        addType(RE2J_REGEXP);
        addType(LIKE_PATTERN);
        addType(JSON_PATH);
        addType(COLOR);
        addType(JSON);
        addType(CODE_POINTS);
        addType(IPADDRESS);
        addType(UUID);
        addParametricType(VarcharParametricType.VARCHAR);
        addParametricType("string", VarcharParametricType.VARCHAR);
        addParametricType(CharParametricType.CHAR);
        addParametricType(DecimalParametricType.DECIMAL);
        addParametricType("dec", DecimalParametricType.DECIMAL);
        addParametricType("numeric", DecimalParametricType.DECIMAL);
        addParametricType(ROW);
        addParametricType(ARRAY);
        addParametricType(MAP);
        addParametricType(FUNCTION);
        addParametricType(QDIGEST);

        for (Type type : types) {
            addType(type);
        }
        parametricTypeCache = CacheBuilder.newBuilder()
                .maximumSize(1000)
                .build();
    }

    // todo remote udf remove redundent typeManager
    public Type getType(TypeManager typeManager, TypeSignature signature)
    {
        Type type = types.get(signature);
        if (type == null) {
            try {
                return parametricTypeCache.get(signature, () -> instantiateParametricType(this.functionAndTypeManager, signature));
            }
            catch (ExecutionException | UncheckedExecutionException e) {
                throwIfUnchecked(e.getCause());
                throw new RuntimeException(e.getCause());
            }
        }
        return type;
    }

    // todo remote udf remove redundent method
    public Type getType(TypeSignature signature)
    {
        return this.getType(this.functionAndTypeManager, signature);
    }

    private Type instantiateParametricType(TypeManager typeManager, TypeSignature signature)
    {
        List<TypeParameter> parameters = new ArrayList<>();

        for (TypeSignatureParameter parameter : signature.getParameters()) {
            TypeParameter typeParameter = TypeParameter.of(parameter, typeManager);
            parameters.add(typeParameter);
        }

        ParametricType parametricType = parametricTypes.get(signature.getBase().toLowerCase(Locale.ENGLISH));
        if (parametricType == null) {
            throw new TypeNotFoundException(signature);
        }

        Type instantiatedType;
        try {
            instantiatedType = parametricType.createType(typeManager, parameters);
        }
        catch (IllegalArgumentException e) {
            throw new TypeNotFoundException(signature, e);
        }

        // TODO: reimplement this check? Currently "varchar(Integer.MAX_VALUE)" fails with "varchar"
        return instantiatedType;
    }

    public Type getParameterizedType(String baseTypeName, List<TypeSignatureParameter> typeParameters)
    {
        // todo remote udf remote redundent args
        return getType(this.functionAndTypeManager, new TypeSignature(baseTypeName, typeParameters));
    }

    public Optional<Type> getCommonSuperType(Type firstType, Type secondType)
    {
        TypeCompatibility compatibility = compatibility(firstType, secondType);
        if (!compatibility.isCompatible()) {
            return Optional.empty();
        }
        return Optional.of(compatibility.getCommonSuperType());
    }

    public boolean canCoerce(Type fromType, Type toType)
    {
        TypeCompatibility typeCompatibility = compatibility(fromType, toType);
        return typeCompatibility.isCoercible();
    }

    /**
     * coerceTypeBase and isCovariantParametrizedType defines all hand-coded rules for type coercion.
     * Other methods should reference these two functions instead of hand-code new rules.
     */
    public Optional<Type> coerceTypeBase(Type sourceType, String resultTypeBase)
    {
        String sourceTypeName = sourceType.getTypeSignature().getBase();
        if (sourceTypeName.equals(resultTypeBase)) {
            return Optional.of(sourceType);
        }

        switch (sourceTypeName) {
            case UnknownType.NAME: {
                switch (resultTypeBase) {
                    case StandardTypes.BOOLEAN:
                    case StandardTypes.BIGINT:
                    case StandardTypes.INTEGER:
                    case StandardTypes.DOUBLE:
                    case StandardTypes.REAL:
                    case StandardTypes.VARBINARY:
                    case StandardTypes.DATE:
                    case StandardTypes.TIME:
                    case StandardTypes.TIME_WITH_TIME_ZONE:
                    case StandardTypes.TIMESTAMP:
                    case StandardTypes.TIMESTAMP_WITH_TIME_ZONE:
                    case StandardTypes.HYPER_LOG_LOG:
                    case SetDigestType.NAME:
                    case StandardTypes.P4_HYPER_LOG_LOG:
                    case StandardTypes.JSON:
                    case StandardTypes.INTERVAL_YEAR_TO_MONTH:
                    case StandardTypes.INTERVAL_DAY_TO_SECOND:
                        // case KHyperLogLogType.NAME:// todo add type or remove this
                    case JoniRegexpType.NAME:
                    case LikePatternType.NAME:
                    case JsonPathType.NAME:
                    case ColorType.NAME:
                    case CodePointsType.NAME:
                        // todo remote udf remove redundent
                        return Optional.of(getType(this.functionAndTypeManager, new TypeSignature(resultTypeBase)));
                    case StandardTypes.VARCHAR:
                        return Optional.of(createVarcharType(0));
                    case StandardTypes.CHAR:
                        return Optional.of(createCharType(0));
                    case StandardTypes.DECIMAL:
                        return Optional.of(createDecimalType(1, 0));
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.TINYINT: {
                switch (resultTypeBase) {
                    case StandardTypes.SMALLINT:
                        return Optional.of(SMALLINT);
                    case StandardTypes.INTEGER:
                        return Optional.of(INTEGER);
                    case StandardTypes.BIGINT:
                        return Optional.of(BIGINT);
                    case StandardTypes.REAL:
                        return Optional.of(REAL);
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    case StandardTypes.DECIMAL:
                        return Optional.of(createDecimalType(3, 0));
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.SMALLINT: {
                switch (resultTypeBase) {
                    case StandardTypes.INTEGER:
                        return Optional.of(INTEGER);
                    case StandardTypes.BIGINT:
                        return Optional.of(BIGINT);
                    case StandardTypes.REAL:
                        return Optional.of(REAL);
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    case StandardTypes.DECIMAL:
                        return Optional.of(createDecimalType(5, 0));
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.INTEGER: {
                switch (resultTypeBase) {
                    case StandardTypes.BIGINT:
                        return Optional.of(BIGINT);
                    case StandardTypes.REAL:
                        return Optional.of(REAL);
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    case StandardTypes.DECIMAL:
                        return Optional.of(createDecimalType(10, 0));
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.BIGINT: {
                switch (resultTypeBase) {
                    case StandardTypes.REAL:
                        return Optional.of(REAL);
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    case StandardTypes.DECIMAL:
                        return Optional.of(createDecimalType(19, 0));
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.DECIMAL: {
                switch (resultTypeBase) {
                    case StandardTypes.REAL:
                        return Optional.of(REAL);
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.REAL: {
                switch (resultTypeBase) {
                    case StandardTypes.DOUBLE:
                        return Optional.of(DOUBLE);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.DATE: {
                switch (resultTypeBase) {
                    case StandardTypes.TIMESTAMP:
                        return Optional.of(TIMESTAMP);
                    case StandardTypes.TIMESTAMP_WITH_TIME_ZONE:
                        return Optional.of(TIMESTAMP_WITH_TIME_ZONE);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.TIME: {
                switch (resultTypeBase) {
                    case StandardTypes.TIME_WITH_TIME_ZONE:
                        return Optional.of(TIME_WITH_TIME_ZONE);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.TIMESTAMP: {
                switch (resultTypeBase) {
                    case StandardTypes.TIMESTAMP_WITH_TIME_ZONE:
                        return Optional.of(TIMESTAMP_WITH_TIME_ZONE);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.VARCHAR: {
                switch (resultTypeBase) {
                    case StandardTypes.CHAR:
                        if (featuresConfig.isLegacyCharToVarcharCoercion()) {
                            return Optional.empty();
                        }

                        VarcharType varcharType = (VarcharType) sourceType;
                        if (varcharType.isUnbounded()) {
                            return Optional.of(createCharType(CharType.MAX_LENGTH));
                        }

                        return Optional.of(createCharType(Math.min(CharType.MAX_LENGTH, varcharType.getLengthSafe())));
                    case JoniRegexpType.NAME:
                        return Optional.of(JONI_REGEXP);
                    case Re2JRegexpType.NAME:
                        return Optional.of(RE2J_REGEXP);
                    case LikePatternType.NAME:
                        return Optional.of(LIKE_PATTERN);
                    case JsonPathType.NAME:
                        return Optional.of(JSON_PATH);
                    case CodePointsType.NAME:
                        return Optional.of(CODE_POINTS);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.CHAR: {
                switch (resultTypeBase) {
                    case StandardTypes.VARCHAR:
                        if (!featuresConfig.isLegacyCharToVarcharCoercion()) {
                            return Optional.empty();
                        }

                        CharType charType = (CharType) sourceType;
                        return Optional.of(createVarcharType(charType.getLength()));
                    case JoniRegexpType.NAME:
                        return Optional.of(JONI_REGEXP);
                    case Re2JRegexpType.NAME:
                        return Optional.of(RE2J_REGEXP);
                    case LikePatternType.NAME:
                        return Optional.of(LIKE_PATTERN);
                    case JsonPathType.NAME:
                        return Optional.of(JSON_PATH);
                    case CodePointsType.NAME:
                        return Optional.of(CODE_POINTS);
                    default:
                        return Optional.empty();
                }
            }
            case StandardTypes.P4_HYPER_LOG_LOG: {
                switch (resultTypeBase) {
                    case StandardTypes.HYPER_LOG_LOG:
                        return Optional.of(HYPER_LOG_LOG);
                    default:
                        return Optional.empty();
                }
            }
            default:
                return Optional.empty();
        }
    }

    private TypeCompatibility compatibility(Type fromType, Type toType)
    {
        if (fromType.equals(toType)) {
            return TypeCompatibility.compatible(toType, true);
        }

        if (fromType.equals(UnknownType.UNKNOWN)) {
            return TypeCompatibility.compatible(toType, true);
        }

        if (toType.equals(UnknownType.UNKNOWN)) {
            return TypeCompatibility.compatible(fromType, false);
        }

        String fromTypeBaseName = fromType.getTypeSignature().getBase();
        String toTypeBaseName = toType.getTypeSignature().getBase();

        if (featuresConfig.isLegacyDateTimestampToVarcharCoercion()) {
            if ((fromTypeBaseName.equals(StandardTypes.DATE) || fromTypeBaseName.equals(StandardTypes.TIMESTAMP)) && toTypeBaseName.equals(StandardTypes.VARCHAR)) {
                return TypeCompatibility.compatible(toType, true);
            }

            if (fromTypeBaseName.equals(StandardTypes.VARCHAR) && (toTypeBaseName.equals(StandardTypes.DATE) || toTypeBaseName.equals(StandardTypes.TIMESTAMP))) {
                return TypeCompatibility.compatible(fromType, true);
            }
        }

        if (fromTypeBaseName.equals(toTypeBaseName)) {
            if (fromTypeBaseName.equals(StandardTypes.DECIMAL)) {
                Type commonSuperType = getCommonSuperTypeForDecimal((DecimalType) fromType, (DecimalType) toType);
                return TypeCompatibility.compatible(commonSuperType, commonSuperType.equals(toType));
            }
            if (fromTypeBaseName.equals(StandardTypes.VARCHAR)) {
                Type commonSuperType = getCommonSuperTypeForVarchar((VarcharType) fromType, (VarcharType) toType);
                return TypeCompatibility.compatible(commonSuperType, commonSuperType.equals(toType));
            }
            if (fromTypeBaseName.equals(StandardTypes.CHAR) && !featuresConfig.isLegacyCharToVarcharCoercion()) {
                Type commonSuperType = getCommonSuperTypeForChar((CharType) fromType, (CharType) toType);
                return TypeCompatibility.compatible(commonSuperType, commonSuperType.equals(toType));
            }
            if (fromTypeBaseName.equals(StandardTypes.ROW)) {
                return typeCompatibilityForRow((RowType) fromType, (RowType) toType);
            }

            if (isCovariantParametrizedType(fromType)) {
                return typeCompatibilityForCovariantParametrizedType(fromType, toType);
            }
            return TypeCompatibility.incompatible();
        }

        Optional<Type> coercedType = coerceTypeBase(fromType, toType.getTypeSignature().getBase());
        if (coercedType.isPresent()) {
            return compatibility(coercedType.get(), toType);
        }

        coercedType = coerceTypeBase(toType, fromType.getTypeSignature().getBase());
        if (coercedType.isPresent()) {
            TypeCompatibility typeCompatibility = compatibility(fromType, coercedType.get());
            if (!typeCompatibility.isCompatible()) {
                return TypeCompatibility.incompatible();
            }
            return TypeCompatibility.compatible(typeCompatibility.getCommonSuperType(), false);
        }

        return TypeCompatibility.incompatible();
    }

    private TypeCompatibility typeCompatibilityForCovariantParametrizedType(Type fromType, Type toType)
    {
        checkState(fromType.getClass().equals(toType.getClass()));
        ImmutableList.Builder<TypeSignatureParameter> commonParameterTypes = ImmutableList.builder();
        List<Type> fromTypeParameters = fromType.getTypeParameters();
        List<Type> toTypeParameters = toType.getTypeParameters();

        if (fromTypeParameters.size() != toTypeParameters.size()) {
            return TypeCompatibility.incompatible();
        }

        boolean coercible = true;
        for (int i = 0; i < fromTypeParameters.size(); i++) {
            TypeCompatibility compatibility = compatibility(fromTypeParameters.get(i), toTypeParameters.get(i));
            if (!compatibility.isCompatible()) {
                return TypeCompatibility.incompatible();
            }
            coercible &= compatibility.isCoercible();
            commonParameterTypes.add(TypeSignatureParameter.of(compatibility.getCommonSuperType().getTypeSignature()));
        }
        String typeBase = fromType.getTypeSignature().getBase();
        // todo remove this.functionAndTypeManager arguments
        return TypeCompatibility.compatible(getType(this.functionAndTypeManager, new TypeSignature(typeBase, commonParameterTypes.build())), coercible);
    }

    private TypeCompatibility typeCompatibilityForRow(RowType firstType, RowType secondType)
    {
        List<Field> firstFields = firstType.getFields();
        List<Field> secondFields = secondType.getFields();
        if (firstFields.size() != secondFields.size()) {
            return TypeCompatibility.incompatible();
        }

        ImmutableList.Builder<Field> fields = ImmutableList.builder();
        boolean coercible = true;
        for (int i = 0; i < firstFields.size(); i++) {
            Type firstFieldType = firstFields.get(i).getType();
            Type secondFieldType = secondFields.get(i).getType();
            TypeCompatibility typeCompatibility = compatibility(firstFieldType, secondFieldType);
            if (!typeCompatibility.isCompatible()) {
                return TypeCompatibility.incompatible();
            }
            Type commonParameterType = typeCompatibility.getCommonSuperType();

            Optional<String> firstParameterName = firstFields.get(i).getName();
            Optional<String> secondParameterName = secondFields.get(i).getName();
            Optional<String> commonName = firstParameterName.equals(secondParameterName) ? firstParameterName : Optional.empty();

            // ignore parameter name for coercible
            coercible &= typeCompatibility.isCoercible();
            fields.add(new Field(commonName, commonParameterType));
        }

        return TypeCompatibility.compatible(RowType.from(fields.build()), coercible);
    }

    private static Type getCommonSuperTypeForChar(CharType firstType, CharType secondType)
    {
        return createCharType(Math.max(firstType.getLength(), secondType.getLength()));
    }

    private static Type getCommonSuperTypeForDecimal(DecimalType firstType, DecimalType secondType)
    {
        int targetScale = Math.max(firstType.getScale(), secondType.getScale());
        int targetPrecision = Math.max(firstType.getPrecision() - firstType.getScale(), secondType.getPrecision() - secondType.getScale()) + targetScale;
        //we allow potential loss of precision here. Overflow checking is done in operators.
        targetPrecision = Math.min(38, targetPrecision);
        return createDecimalType(targetPrecision, targetScale);
    }

    private static Type getCommonSuperTypeForVarchar(VarcharType firstType, VarcharType secondType)
    {
        if (firstType.isUnbounded() || secondType.isUnbounded()) {
            return createUnboundedVarcharType();
        }

        return createVarcharType(Math.max(firstType.getLength().get(), secondType.getLength().get()));
    }

    public boolean isTypeOnlyCoercion(Type source, Type result)
    {
        if (source.equals(result)) {
            return true;
        }

        if (!canCoerce(source, result)) {
            return false;
        }

        if (source instanceof VarcharType && result instanceof VarcharType) {
            return true;
        }

        if (source instanceof DecimalType && result instanceof DecimalType) {
            DecimalType sourceDecimal = (DecimalType) source;
            DecimalType resultDecimal = (DecimalType) result;
            boolean sameDecimalSubtype = (sourceDecimal.isShort() && resultDecimal.isShort())
                    || (!sourceDecimal.isShort() && !resultDecimal.isShort());
            boolean sameScale = sourceDecimal.getScale() == resultDecimal.getScale();
            boolean sourcePrecisionIsLessOrEqualToResultPrecision = sourceDecimal.getPrecision() <= resultDecimal.getPrecision();
            return sameDecimalSubtype && sameScale && sourcePrecisionIsLessOrEqualToResultPrecision;
        }

        String sourceTypeBase = source.getTypeSignature().getBase();
        String resultTypeBase = result.getTypeSignature().getBase();

        if (sourceTypeBase.equals(resultTypeBase) && isCovariantParametrizedType(source)) {
            List<Type> sourceTypeParameters = source.getTypeParameters();
            List<Type> resultTypeParameters = result.getTypeParameters();
            checkState(sourceTypeParameters.size() == resultTypeParameters.size());
            for (int i = 0; i < sourceTypeParameters.size(); i++) {
                if (!isTypeOnlyCoercion(sourceTypeParameters.get(i), resultTypeParameters.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean isCovariantParametrizedType(Type type)
    {
        // if we ever introduce contravariant, this function should be changed to return an enumeration: INVARIANT, COVARIANT, CONTRAVARIANT
        return type instanceof MapType || type instanceof ArrayType;
    }

    public static class TypeCompatibility
    {
        private final Optional<Type> commonSuperType;
        private final boolean coercible;

        // Do not call constructor directly. Use factory methods.
        private TypeCompatibility(Optional<Type> commonSuperType, boolean coercible)
        {
            // Assert that: coercible => commonSuperType.isPresent
            // The factory API is designed such that this is guaranteed.
            checkArgument(!coercible || commonSuperType.isPresent());

            this.commonSuperType = commonSuperType;
            this.coercible = coercible;
        }

        private static TypeCompatibility compatible(Type commonSuperType, boolean coercible)
        {
            return new TypeCompatibility(Optional.of(commonSuperType), coercible);
        }

        private static TypeCompatibility incompatible()
        {
            return new TypeCompatibility(Optional.empty(), false);
        }

        public boolean isCompatible()
        {
            return commonSuperType.isPresent();
        }

        public Type getCommonSuperType()
        {
            checkState(commonSuperType.isPresent(), "Types are not compatible");
            return commonSuperType.get();
        }

        public boolean isCoercible()
        {
            return coercible;
        }
    }

    public List<Type> getTypes()
    {
        return ImmutableList.copyOf(types.values());
    }

    public Collection<ParametricType> getParametricTypes()
    {
        return ImmutableList.copyOf(parametricTypes.values());
    }

    public void addType(Type type)
    {
        requireNonNull(type, "type is null");
        Type existingType = types.putIfAbsent(type.getTypeSignature(), type);
        checkState(existingType == null || existingType.equals(type), "Type %s is already registered", type);
    }

    public void addParametricType(ParametricType parametricType)
    {
        String name = parametricType.getName().toLowerCase(Locale.ENGLISH);
        checkArgument(!parametricTypes.containsKey(name), "Parametric type already registered: %s", name);
        parametricTypes.putIfAbsent(name, parametricType);
    }

    public void addType(String alias, Type type)
    {
        requireNonNull(alias, "alias is null");
        requireNonNull(type, "type is null");

        Type existingType = types.putIfAbsent(new TypeSignature(alias), type);
        checkState(existingType == null || existingType.equals(type), "Alias %s is already mapped to %s", alias, type);
    }

    public void addParametricType(String alias, ParametricType parametricType)
    {
        requireNonNull(alias, "alias is null");

        checkArgument(!parametricTypes.containsKey(alias), "Parametric type already registered: %s", alias);
        parametricTypes.putIfAbsent(alias, parametricType);
    }
}
