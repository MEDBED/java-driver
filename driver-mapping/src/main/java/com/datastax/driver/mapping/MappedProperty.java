/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.mapping;

import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.TypeCodec;
import com.datastax.driver.mapping.annotations.*;
import com.google.common.collect.ComparisonChain;
import com.google.common.reflect.TypeToken;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * A bean property mapped to a table column or a UDT field.
 *
 * @param <T> The component classe where this property belongs (either
 *            a {@link com.datastax.driver.mapping.annotations.Table @Table}
 *            or {@link com.datastax.driver.mapping.annotations.UDT @UDT}
 *            annotated class).
 */
class MappedProperty<T> implements Comparable<MappedProperty<T>> {

    enum Kind {PARTITION_KEY, CLUSTERING_COLUMN, REGULAR, COMPUTED}

    private final PropertyDescriptor property;
    private final Class<T> componentClass;
    private final Field field;
    private final Method getter;
    private final Method setter;
    private final Map<Class<? extends Annotation>, Annotation> annotations;

    MappedProperty(PropertyDescriptor property, Class<T> componentClass) {
        this.property = property;
        this.componentClass = componentClass;
        field = ReflectionUtils.findField(property.getName(), componentClass);
        getter = ReflectionUtils.findGetter(property);
        setter = ReflectionUtils.findSetter(property);
        annotations = ReflectionUtils.findAnnotations(property, componentClass);
    }

    Class<T> componentClass() {
        return componentClass;
    }

    boolean hasAnnotation(Class<? extends Annotation> annotationClass) {
        return annotations.containsKey(annotationClass);
    }

    Collection<Annotation> annotations() {
        return annotations.values();
    }

    @SuppressWarnings("unchecked")
    <A extends Annotation> A annotation(Class<A> annotationClass) {
        return (A) annotations.get(annotationClass);
    }

    boolean isComputed() {
        return hasAnnotation(Computed.class);
    }

    boolean isTransient() {
        return hasAnnotation(Transient.class);
    }

    boolean isPartitionKey() {
        return hasAnnotation(PartitionKey.class);
    }

    boolean isClusteringColumn() {
        return hasAnnotation(ClusteringColumn.class);
    }

    Method getter() {
        return getter;
    }

    Method setter() {
        return setter;
    }

    String name() {
        return property.getName();
    }

    TypeToken<?> type() {
        Type type;
        if (field != null)
            type = field.getGenericType();
        else if (getter != null)
            type = getter.getGenericReturnType();
        else
            // this will not work for generic types
            type = property.getPropertyType();
        return TypeToken.of(type);
    }

    Kind kind() {
        if (isPartitionKey()) {
            return Kind.PARTITION_KEY;
        }
        if (isClusteringColumn()) {
            return Kind.CLUSTERING_COLUMN;
        }
        if (isComputed()) {
            return Kind.COMPUTED;
        }
        return Kind.REGULAR;
    }

    int position() {
        if (isPartitionKey()) {
            return annotation(PartitionKey.class).value();
        }
        if (isClusteringColumn()) {
            return annotation(ClusteringColumn.class).value();
        }
        return -1;
    }

    String columnName() {
        Column column = annotation(Column.class);
        if (column != null && !column.name().isEmpty()) {
            return Metadata.quote(column.caseSensitive() ? column.name() : column.name().toLowerCase());
        }
        com.datastax.driver.mapping.annotations.Field udtField = annotation(com.datastax.driver.mapping.annotations.Field.class);
        if (udtField != null && !udtField.name().isEmpty()) {
            return Metadata.quote(udtField.caseSensitive() ? udtField.name() : udtField.name().toLowerCase());
        }
        if (isComputed()) {
            return annotation(Computed.class).value();
        }
        return Metadata.quote(name().toLowerCase());
    }

    TypeCodec<Object> customCodec() {
        Class<? extends TypeCodec<?>> codecClass = getCodecClass();
        if (codecClass.equals(Defaults.NoCodec.class))
            return null;
        try {
            @SuppressWarnings("unchecked")
            TypeCodec<Object> instance = (TypeCodec<Object>) codecClass.newInstance();
            return instance;
        } catch (Exception e) {
            throw new IllegalArgumentException(String.format(
                    "Cannot create an instance of custom codec %s for property %s",
                    codecClass, property
            ), e);
        }
    }

    private Class<? extends TypeCodec<?>> getCodecClass() {
        Column column = annotation(Column.class);
        if (column != null)
            return column.codec();
        com.datastax.driver.mapping.annotations.Field udtField = annotation(com.datastax.driver.mapping.annotations.Field.class);
        if (udtField != null)
            return udtField.codec();
        return Defaults.NoCodec.class;
    }

    @Override
    public int compareTo(MappedProperty<T> that) {
        return ComparisonChain.start()
                .compare(this.kind(), that.kind())
                .compare(this.position(), that.position())
                .compare(this.name(), that.name())
                .result();
    }

}
