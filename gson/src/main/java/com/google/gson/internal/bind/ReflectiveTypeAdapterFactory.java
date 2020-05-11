/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal.bind;

import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.$Gson$Types;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.ObjectConstructor;
import com.google.gson.internal.Primitives;
import com.google.gson.internal.reflect.ReflectionAccessor;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type adapter that reflects over the fields and methods of a class.
 */
//类型适配器，反映类的字段和方法。
public final class ReflectiveTypeAdapterFactory implements TypeAdapterFactory {
    //实例构造器
    private final ConstructorConstructor constructorConstructor;
    //属性的命名策略
    private final FieldNamingStrategy fieldNamingPolicy;
    //排除的属性值
    private final Excluder excluder;
    private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;
    private final ReflectionAccessor accessor = ReflectionAccessor.getInstance();

    public ReflectiveTypeAdapterFactory(ConstructorConstructor constructorConstructor,
                                        FieldNamingStrategy fieldNamingPolicy, Excluder excluder,
                                        JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory) {
        this.constructorConstructor = constructorConstructor;
        this.fieldNamingPolicy = fieldNamingPolicy;
        this.excluder = excluder;
        this.jsonAdapterFactory = jsonAdapterFactory;
    }

    public boolean excludeField(Field f, boolean serialize) {
        return excludeField(f, serialize, excluder);
    }

    static boolean excludeField(Field f, boolean serialize, Excluder excluder) {
        return !excluder.excludeClass(f.getType(), serialize) && !excluder.excludeField(f, serialize);
    }

    /**
     * first element holds the default name
     */
    //获取属性的名称，会使用对应的名称策略或者使用的@SerializedName注解
    private List<String> getFieldNames(Field f) {
        SerializedName annotation = f.getAnnotation(SerializedName.class);
        if (annotation == null) {
            String name = fieldNamingPolicy.translateName(f);
            return Collections.singletonList(name);
        }

        String serializedName = annotation.value();
        String[] alternates = annotation.alternate();
        if (alternates.length == 0) {
            return Collections.singletonList(serializedName);
        }

        List<String> fieldNames = new ArrayList<String>(alternates.length + 1);
        fieldNames.add(serializedName);
        for (String alternate : alternates) {
            fieldNames.add(alternate);
        }
        return fieldNames;
    }

    @Override
    public <T> TypeAdapter<T> create(Gson gson, final TypeToken<T> type) {
        Class<? super T> raw = type.getRawType();
        //如果不是Object的子类类，则不匹配，只直接返回
        if (!Object.class.isAssignableFrom(raw)) {
            return null; // it's a primitive!
        }
        //获取Type所对应构造器
        ObjectConstructor<T> constructor = constructorConstructor.get(type);
        //创建一个TypeAdapter 重点方法***getBoundFields
        return new Adapter<T>(constructor, getBoundFields(gson, type, raw));
    }

    private ReflectiveTypeAdapterFactory.BoundField createBoundField(
            final Gson context, final Field field, final String name,
            final TypeToken<?> fieldType, boolean serialize, boolean deserialize) {
        //是否是原始数据类型  boolean,int,char,float,double等
        final boolean isPrimitive = Primitives.isPrimitive(fieldType.getRawType());
        // special casing primitives here saves ~5% on Android...
        JsonAdapter annotation = field.getAnnotation(JsonAdapter.class);
        TypeAdapter<?> mapped = null;
        if (annotation != null) {
            //如果属性使用了JsonAdapter，则使用其
            mapped = jsonAdapterFactory.getTypeAdapter(constructorConstructor, context, fieldType, annotation);
        }
        final boolean jsonAdapterPresent = mapped != null;
        //尝试获取类型所对应的TypeAdapter
        if (mapped == null) mapped = context.getAdapter(fieldType);

        final TypeAdapter<?> typeAdapter = mapped;
        return new ReflectiveTypeAdapterFactory.BoundField(name, serialize, deserialize) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            // the type adapter and field type always agree
            @Override
            void write(JsonWriter writer, Object value)throws IOException, IllegalAccessException {
                Object fieldValue = field.get(value);
                TypeAdapter t = jsonAdapterPresent ? typeAdapter : new TypeAdapterRuntimeTypeWrapper(context, typeAdapter, fieldType.getType());
                t.write(writer, fieldValue);
            }

            @Override
            void read(JsonReader reader, Object value) throws IOException, IllegalAccessException {
                Object fieldValue = typeAdapter.read(reader);
                if (fieldValue != null || !isPrimitive) {
                    field.set(value, fieldValue);
                }
            }

            @Override
            public boolean writeField(Object value) throws IOException, IllegalAccessException {
                if (!serialized) return false;
                Object fieldValue = field.get(value);
                return fieldValue != value; // avoid recursion for example for Throwable.cause
            }
        };
    }

    //会对raw类进行解析，获取到期对应的属性值，以及其是否参与序列化，或者反序列化
    private Map<String, BoundField> getBoundFields(Gson context, TypeToken<?> type, Class<?> raw) {
        Map<String, BoundField> result = new LinkedHashMap<String, BoundField>();
        //接口直接返回
        if (raw.isInterface()) {
            return result;
        }
        Type declaredType = type.getType();
        while (raw != Object.class) {
            //获取所有属性
            Field[] fields = raw.getDeclaredFields();
            for (Field field : fields) {
                //是否序列化，主要是根据字段名称，以及是否忽略等相关设置信息进行判断
                boolean serialize = excludeField(field, true);
                //是否反序列化
                boolean deserialize = excludeField(field, false);
                //既不参与序列化，也不参与反序列化，则直接跳过
                if (!serialize && !deserialize) {
                    continue;
                }
                //设置属性可见
                accessor.makeAccessible(field);
                Type fieldType = $Gson$Types.resolve(type.getType(), raw, field.getGenericType());
                List<String> fieldNames = getFieldNames(field);
                BoundField previous = null;
                for (int i = 0, size = fieldNames.size(); i < size; ++i) {
                    String name = fieldNames.get(i);
                    if (i != 0) serialize = false; // only serialize the default name
                    //根据filed、type、以及是否支持序列化和反序列化来创建一个BoundField对象
                    BoundField boundField = createBoundField(context, field, name, TypeToken.get(fieldType), serialize, deserialize);
                    //将属性名称作为key，boundField作为value保存到result中
                    BoundField replaced = result.put(name, boundField);
                    //如果之前出现过name这个属性的话，这里机会导致previous不为空，从而报错
                    if (previous == null) previous = replaced;
                }
                if (previous != null) {
                    throw new IllegalArgumentException(declaredType + " declares multiple JSON fields named " + previous.name);
                }
            }
            //
            type = TypeToken.get($Gson$Types.resolve(type.getType(), raw, raw.getGenericSuperclass()));
            //获取父类类型，最终肯定会获取到Object，从而终止整个循环
            raw = type.getRawType();
        }
        return result;
    }

    static abstract class BoundField {
        final String name;
        final boolean serialized;
        final boolean deserialized;

        protected BoundField(String name, boolean serialized, boolean deserialized) {
            this.name = name;
            this.serialized = serialized;
            this.deserialized = deserialized;
        }

        abstract boolean writeField(Object value) throws IOException, IllegalAccessException;

        abstract void write(JsonWriter writer, Object value) throws IOException, IllegalAccessException;

        abstract void read(JsonReader reader, Object value) throws IOException, IllegalAccessException;
    }

    //继承TypeAdapter的一个类
    public static final class Adapter<T> extends TypeAdapter<T> {
        private final ObjectConstructor<T> constructor;
        private final Map<String, BoundField> boundFields;

        Adapter(ObjectConstructor<T> constructor, Map<String, BoundField> boundFields) {
            this.constructor = constructor;
            this.boundFields = boundFields;
        }

        @Override
        public T read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            T instance = constructor.construct();
            try {
                in.beginObject();//从"{"开始
                while (in.hasNext()) {
                    String name = in.nextName();//逐个获取属性
                    BoundField field = boundFields.get(name);//从map中获取属性所对应的的BoundField类
                    if (field == null || !field.deserialized) {
                        in.skipValue();
                    } else {
                        //属性值能够序列化，则进行序列化操作。这里会对field进行赋值
                        field.read(in, instance);
                    }
                }
            } catch (IllegalStateException e) {
                throw new JsonSyntaxException(e);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            in.endObject();
            return instance;
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            //输出"{
            out.beginObject();
            try {
                for (BoundField boundField : boundFields.values()) {
                    if (boundField.writeField(value)) {
                        out.name(boundField.name);//先输出属性名称
                        boundField.write(out, value);//再输出属性值
                    }
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            //输出"}"
            out.endObject();
        }
    }
}
