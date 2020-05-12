/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson;

import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.GsonBuildConfig;
import com.google.gson.internal.Primitives;
import com.google.gson.internal.Streams;
import com.google.gson.internal.bind.ArrayTypeAdapter;
import com.google.gson.internal.bind.CollectionTypeAdapterFactory;
import com.google.gson.internal.bind.DateTypeAdapter;
import com.google.gson.internal.bind.JsonAdapterAnnotationTypeAdapterFactory;
import com.google.gson.internal.bind.JsonTreeReader;
import com.google.gson.internal.bind.JsonTreeWriter;
import com.google.gson.internal.bind.MapTypeAdapterFactory;
import com.google.gson.internal.bind.ObjectTypeAdapter;
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory;
import com.google.gson.internal.bind.SqlDateTypeAdapter;
import com.google.gson.internal.bind.TimeTypeAdapter;
import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;

/**
 * This is the main class for using Gson. Gson is typically used by first constructing a
 * Gson instance and then invoking {@link #toJson(Object)} or {@link #fromJson(String, Class)}
 * methods on it. Gson instances are Thread-safe so you can reuse them freely across multiple
 * threads.
 *
 * <p>You can create a Gson instance by invoking {@code new Gson()} if the default configuration
 * is all you need. You can also use {@link GsonBuilder} to build a Gson instance with various
 * configuration options such as versioning support, pretty printing, custom
 * {@link JsonSerializer}s, {@link JsonDeserializer}s, and {@link InstanceCreator}s.</p>
 *
 * <p>Here is an example of how Gson is used for a simple Class:
 *
 * <pre>
 * Gson gson = new Gson(); // Or use new GsonBuilder().create();
 * MyType target = new MyType();
 * String json = gson.toJson(target); // serializes target to Json
 * MyType target2 = gson.fromJson(json, MyType.class); // deserializes json into target2
 * </pre></p>
 *
 * <p>If the object that your are serializing/deserializing is a {@code ParameterizedType}
 * (i.e. contains at least one type parameter and may be an array) then you must use the
 * {@link #toJson(Object, Type)} or {@link #fromJson(String, Type)} method. Here is an
 * example for serializing and deserializing a {@code ParameterizedType}:
 *
 * <pre>
 * Type listType = new TypeToken&lt;List&lt;String&gt;&gt;() {}.getType();
 * List&lt;String&gt; target = new LinkedList&lt;String&gt;();
 * target.add("blah");
 *
 * Gson gson = new Gson();
 * String json = gson.toJson(target, listType);
 * List&lt;String&gt; target2 = gson.fromJson(json, listType);
 * </pre></p>
 *
 * <p>See the <a href="https://sites.google.com/site/gson/gson-user-guide">Gson User Guide</a>
 * for a more complete set of examples.</p>
 *
 * @author Inderjeet Singh
 * @author Joel Leitch
 * @author Jesse Wilson
 * @see com.google.gson.reflect.TypeToken
 */
public final class Gson {
    static final boolean DEFAULT_JSON_NON_EXECUTABLE = false;
    static final boolean DEFAULT_LENIENT = false;
    static final boolean DEFAULT_PRETTY_PRINT = false;
    static final boolean DEFAULT_ESCAPE_HTML = true;
    static final boolean DEFAULT_SERIALIZE_NULLS = false;
    static final boolean DEFAULT_COMPLEX_MAP_KEYS = false;
    static final boolean DEFAULT_SPECIALIZE_FLOAT_VALUES = false;

    private static final TypeToken<?> NULL_KEY_SURROGATE = TypeToken.get(Object.class);
    private static final String JSON_NON_EXECUTABLE_PREFIX = ")]}'\n";

    /**
     * This thread local guards against reentrant calls to getAdapter(). In
     * certain object graphs, creating an adapter for a type may recursively
     * require an adapter for the same type! Without intervention, the recursive
     * lookup would stack overflow. We cheat by returning a proxy type adapter.
     * The proxy is wired up once the initial adapter has been created.
     */
    private final ThreadLocal<Map<TypeToken<?>, FutureTypeAdapter<?>>> calls = new ThreadLocal<Map<TypeToken<?>, FutureTypeAdapter<?>>>();
    //缓存的 TypeToken和TpyeAdapter对应关系，采用ConcurrentHashMap，保障了多个线程操作时，不会发生线程安全问题
    private final Map<TypeToken<?>, TypeAdapter<?>> typeTokenCache = new ConcurrentHashMap<TypeToken<?>, TypeAdapter<?>>();
    //实例构造器
    private final ConstructorConstructor constructorConstructor;
    //json对应的
    private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;
    //TypeAdapterFactory列表，里面包含了对各种常用的对象类型的解析工厂，
    // 如果需要自定义解析方法的话，需要通过注册，将其添加到这个列表中
    final List<TypeAdapterFactory> factories;
    //用于配置一些你不希望被转换成JSON格式的对象的成员变量的
    final Excluder excluder;
    //字段命名策略，默认的是属性名称
    final FieldNamingStrategy fieldNamingStrategy;
    final Map<Type, InstanceCreator<?>> instanceCreators;
    //是否序列化空值。默认为false。如果为true，那么如果字段没有设置对应的值，则会序列化为 a:null 这种
    final boolean serializeNulls;
    //
    final boolean complexMapKeySerialization;
    final boolean generateNonExecutableJson;
    final boolean htmlSafe;
    final boolean prettyPrinting;
    final boolean lenient;
    final boolean serializeSpecialFloatingPointValues;
    final String datePattern;
    final int dateStyle;
    final int timeStyle;
    final LongSerializationPolicy longSerializationPolicy;
    final List<TypeAdapterFactory> builderFactories;
    final List<TypeAdapterFactory> builderHierarchyFactories;

    /**
     * Constructs a Gson object with default configuration. The default configuration has the
     * following settings:
     * <ul>
     * <li>The JSON generated by <code>toJson</code> methods is in compact representation. This
     * means that all the unneeded white-space is removed. You can change this behavior with
     * {@link GsonBuilder#setPrettyPrinting()}. </li>
     * <li>The generated JSON omits all the fields that are null. Note that nulls in arrays are
     * kept as is since an array is an ordered list. Moreover, if a field is not null, but its
     * generated JSON is empty, the field is kept. You can configure Gson to serialize null values
     * by setting {@link GsonBuilder#serializeNulls()}.</li>
     * <li>Gson provides default serialization and deserialization for Enums, {@link Map},
     * {@link java.net.URL}, {@link java.net.URI}, {@link java.util.Locale}, {@link java.util.Date},
     * {@link java.math.BigDecimal}, and {@link java.math.BigInteger} classes. If you would prefer
     * to change the default representation, you can do so by registering a type adapter through
     * {@link GsonBuilder#registerTypeAdapter(Type, Object)}. </li>
     * <li>The default Date format is same as {@link java.text.DateFormat#DEFAULT}. This format
     * ignores the millisecond portion of the date during serialization. You can change
     * this by invoking {@link GsonBuilder#setDateFormat(int)} or
     * {@link GsonBuilder#setDateFormat(String)}. </li>
     * <li>By default, Gson ignores the {@link com.google.gson.annotations.Expose} annotation.
     * You can enable Gson to serialize/deserialize only those fields marked with this annotation
     * through {@link GsonBuilder#excludeFieldsWithoutExposeAnnotation()}. </li>
     * <li>By default, Gson ignores the {@link com.google.gson.annotations.Since} annotation. You
     * can enable Gson to use this annotation through {@link GsonBuilder#setVersion(double)}.</li>
     * <li>The default field naming policy for the output Json is same as in Java. So, a Java class
     * field <code>versionNumber</code> will be output as <code>&quot;versionNumber&quot;</code> in
     * Json. The same rules are applied for mapping incoming Json to the Java classes. You can
     * change this policy through {@link GsonBuilder#setFieldNamingPolicy(FieldNamingPolicy)}.</li>
     * <li>By default, Gson excludes <code>transient</code> or <code>static</code> fields from
     * consideration for serialization and deserialization. You can change this behavior through
     * {@link GsonBuilder#excludeFieldsWithModifiers(int...)}.</li>
     * </ul>
     */
    public Gson() {
        this(Excluder.DEFAULT, FieldNamingPolicy.IDENTITY,
                Collections.<Type, InstanceCreator<?>>emptyMap(), DEFAULT_SERIALIZE_NULLS,
                DEFAULT_COMPLEX_MAP_KEYS, DEFAULT_JSON_NON_EXECUTABLE, DEFAULT_ESCAPE_HTML,
                DEFAULT_PRETTY_PRINT, DEFAULT_LENIENT, DEFAULT_SPECIALIZE_FLOAT_VALUES,
                LongSerializationPolicy.DEFAULT, null, DateFormat.DEFAULT, DateFormat.DEFAULT,
                Collections.<TypeAdapterFactory>emptyList(), Collections.<TypeAdapterFactory>emptyList(),
                Collections.<TypeAdapterFactory>emptyList());
    }

    Gson(Excluder excluder, FieldNamingStrategy fieldNamingStrategy,
         Map<Type, InstanceCreator<?>> instanceCreators, boolean serializeNulls,
         boolean complexMapKeySerialization, boolean generateNonExecutableGson, boolean htmlSafe,
         boolean prettyPrinting, boolean lenient, boolean serializeSpecialFloatingPointValues,
         LongSerializationPolicy longSerializationPolicy, String datePattern, int dateStyle,
         int timeStyle, List<TypeAdapterFactory> builderFactories,
         List<TypeAdapterFactory> builderHierarchyFactories,
         List<TypeAdapterFactory> factoriesToBeAdded) {
        this.excluder = excluder;
        this.fieldNamingStrategy = fieldNamingStrategy;
        this.instanceCreators = instanceCreators;
        this.constructorConstructor = new ConstructorConstructor(instanceCreators);
        this.serializeNulls = serializeNulls;
        this.complexMapKeySerialization = complexMapKeySerialization;
        this.generateNonExecutableJson = generateNonExecutableGson;
        this.htmlSafe = htmlSafe;
        this.prettyPrinting = prettyPrinting;
        this.lenient = lenient;
        this.serializeSpecialFloatingPointValues = serializeSpecialFloatingPointValues;
        this.longSerializationPolicy = longSerializationPolicy;
        this.datePattern = datePattern;
        this.dateStyle = dateStyle;
        this.timeStyle = timeStyle;
        this.builderFactories = builderFactories;
        this.builderHierarchyFactories = builderHierarchyFactories;

        List<TypeAdapterFactory> factories = new ArrayList<TypeAdapterFactory>();

        // built-in type adapters that cannot be overridden
        factories.add(TypeAdapters.JSON_ELEMENT_FACTORY);
        //object类型的
        factories.add(ObjectTypeAdapter.FACTORY);

        // the excluder must precede all adapters that handle user-defined types
        //excluder必须在处理用户自定义的类型之前进行添加
        factories.add(excluder);

        // users' type adapters
        //添加用户自定义的TypeAdapterFactory
        factories.addAll(factoriesToBeAdded);
        //基础类型的TypeAdapterFactory
        // type adapters for basic platform types
        factories.add(TypeAdapters.STRING_FACTORY);
        factories.add(TypeAdapters.INTEGER_FACTORY);
        factories.add(TypeAdapters.BOOLEAN_FACTORY);
        factories.add(TypeAdapters.BYTE_FACTORY);
        factories.add(TypeAdapters.SHORT_FACTORY);
        TypeAdapter<Number> longAdapter = longAdapter(longSerializationPolicy);
        factories.add(TypeAdapters.newFactory(long.class, Long.class, longAdapter));
        factories.add(TypeAdapters.newFactory(double.class, Double.class,
                doubleAdapter(serializeSpecialFloatingPointValues)));
        factories.add(TypeAdapters.newFactory(float.class, Float.class,
                floatAdapter(serializeSpecialFloatingPointValues)));
        factories.add(TypeAdapters.NUMBER_FACTORY);
        factories.add(TypeAdapters.ATOMIC_INTEGER_FACTORY);
        factories.add(TypeAdapters.ATOMIC_BOOLEAN_FACTORY);
        factories.add(TypeAdapters.newFactory(AtomicLong.class, atomicLongAdapter(longAdapter)));
        factories.add(TypeAdapters.newFactory(AtomicLongArray.class, atomicLongArrayAdapter(longAdapter)));
        factories.add(TypeAdapters.ATOMIC_INTEGER_ARRAY_FACTORY);
        factories.add(TypeAdapters.CHARACTER_FACTORY);
        factories.add(TypeAdapters.STRING_BUILDER_FACTORY);
        factories.add(TypeAdapters.STRING_BUFFER_FACTORY);
        factories.add(TypeAdapters.newFactory(BigDecimal.class, TypeAdapters.BIG_DECIMAL));
        factories.add(TypeAdapters.newFactory(BigInteger.class, TypeAdapters.BIG_INTEGER));
        factories.add(TypeAdapters.URL_FACTORY);
        factories.add(TypeAdapters.URI_FACTORY);
        factories.add(TypeAdapters.UUID_FACTORY);
        factories.add(TypeAdapters.CURRENCY_FACTORY);
        factories.add(TypeAdapters.LOCALE_FACTORY);
        factories.add(TypeAdapters.INET_ADDRESS_FACTORY);
        factories.add(TypeAdapters.BIT_SET_FACTORY);
        factories.add(DateTypeAdapter.FACTORY);
        factories.add(TypeAdapters.CALENDAR_FACTORY);
        factories.add(TimeTypeAdapter.FACTORY);
        factories.add(SqlDateTypeAdapter.FACTORY);
        factories.add(TypeAdapters.TIMESTAMP_FACTORY);
        factories.add(ArrayTypeAdapter.FACTORY);
        factories.add(TypeAdapters.CLASS_FACTORY);
        // type adapters for composite and user-defined types
        //用于组合和用户定义类型的类型适配器
        factories.add(new CollectionTypeAdapterFactory(constructorConstructor));
        factories.add(new MapTypeAdapterFactory(constructorConstructor, complexMapKeySerialization));
        //用来进行@JsonAdapter注解的处理
        this.jsonAdapterFactory = new JsonAdapterAnnotationTypeAdapterFactory(constructorConstructor);
        factories.add(jsonAdapterFactory);
        factories.add(TypeAdapters.ENUM_FACTORY);
        //用来进行反射的处理
        factories.add(new ReflectiveTypeAdapterFactory(constructorConstructor, fieldNamingStrategy, excluder, jsonAdapterFactory));

        this.factories = Collections.unmodifiableList(factories);
    }

    /**
     * Returns a new GsonBuilder containing all custom factories and configuration used by the current
     * instance.
     *
     * @return a GsonBuilder instance.
     */
    public GsonBuilder newBuilder() {
        return new GsonBuilder(this);
    }

    public Excluder excluder() {
        return excluder;
    }

    public FieldNamingStrategy fieldNamingStrategy() {
        return fieldNamingStrategy;
    }

    public boolean serializeNulls() {
        return serializeNulls;
    }

    public boolean htmlSafe() {
        return htmlSafe;
    }

    private TypeAdapter<Number> doubleAdapter(boolean serializeSpecialFloatingPointValues) {
        if (serializeSpecialFloatingPointValues) {
            return TypeAdapters.DOUBLE;
        }
        return new TypeAdapter<Number>() {
            @Override
            public Double read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return in.nextDouble();
            }

            @Override
            public void write(JsonWriter out, Number value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                double doubleValue = value.doubleValue();
                checkValidFloatingPoint(doubleValue);
                out.value(value);
            }
        };
    }

    private TypeAdapter<Number> floatAdapter(boolean serializeSpecialFloatingPointValues) {
        if (serializeSpecialFloatingPointValues) {
            return TypeAdapters.FLOAT;
        }
        return new TypeAdapter<Number>() {
            @Override
            public Float read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return (float) in.nextDouble();
            }

            @Override
            public void write(JsonWriter out, Number value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                float floatValue = value.floatValue();
                checkValidFloatingPoint(floatValue);
                out.value(value);
            }
        };
    }

    static void checkValidFloatingPoint(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(value
                    + " is not a valid double value as per JSON specification. To override this"
                    + " behavior, use GsonBuilder.serializeSpecialFloatingPointValues() method.");
        }
    }

    private static TypeAdapter<Number> longAdapter(LongSerializationPolicy longSerializationPolicy) {
        if (longSerializationPolicy == LongSerializationPolicy.DEFAULT) {
            return TypeAdapters.LONG;
        }
        return new TypeAdapter<Number>() {
            @Override
            public Number read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                return in.nextLong();
            }

            @Override
            public void write(JsonWriter out, Number value) throws IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.toString());
            }
        };
    }

    private static TypeAdapter<AtomicLong> atomicLongAdapter(final TypeAdapter<Number> longAdapter) {
        return new TypeAdapter<AtomicLong>() {
            @Override
            public void write(JsonWriter out, AtomicLong value) throws IOException {
                longAdapter.write(out, value.get());
            }

            @Override
            public AtomicLong read(JsonReader in) throws IOException {
                Number value = longAdapter.read(in);
                return new AtomicLong(value.longValue());
            }
        }.nullSafe();
    }

    private static TypeAdapter<AtomicLongArray> atomicLongArrayAdapter(final TypeAdapter<Number> longAdapter) {
        return new TypeAdapter<AtomicLongArray>() {
            @Override
            public void write(JsonWriter out, AtomicLongArray value) throws IOException {
                out.beginArray();
                for (int i = 0, length = value.length(); i < length; i++) {
                    longAdapter.write(out, value.get(i));
                }
                out.endArray();
            }

            @Override
            public AtomicLongArray read(JsonReader in) throws IOException {
                List<Long> list = new ArrayList<Long>();
                in.beginArray();
                while (in.hasNext()) {
                    long value = longAdapter.read(in).longValue();
                    list.add(value);
                }
                in.endArray();
                int length = list.size();
                AtomicLongArray array = new AtomicLongArray(length);
                for (int i = 0; i < length; ++i) {
                    array.set(i, list.get(i));
                }
                return array;
            }
        }.nullSafe();
    }

    /**
     * Returns the type adapter for {@code} type.
     *
     * @throws IllegalArgumentException if this GSON cannot serialize and
     *                                  deserialize {@code type}.
     */
    @SuppressWarnings("unchecked")

    public <T> TypeAdapter<T> getAdapter(TypeToken<T> type) {
        //先尝试从缓存获取，缓存使用的是ConcurrentHashMap，能够保障线程的安全性。
        TypeAdapter<?> cached = typeTokenCache.get(type == null ? NULL_KEY_SURROGATE : type);
        if (cached != null) {
            //如果获取到则直接返回
            return (TypeAdapter<T>) cached;
        }
        //all属于一个ThreadLocal变量，保存了Map对象，而map对象则缓存了FutureTypeAdapter类型。
        //获取当前线程对应的解析器。这里为嘛用一个线程安全的calls？？好奇
        Map<TypeToken<?>, FutureTypeAdapter<?>> threadCalls = calls.get();
        boolean requiresThreadLocalCleanup = false;
        if (threadCalls == null) {//如果为空，则创建，保证后面不会出现空指针问题
            threadCalls = new HashMap<TypeToken<?>, FutureTypeAdapter<?>>();
            calls.set(threadCalls);
            //最后需要根据这个字段进行线程的清空处理
            requiresThreadLocalCleanup = true;
        }
        // the key and value type parameters always agree
        //如果从ThreadLocal内部的Map缓存中获取到对应的TypeAdapter则直接返回
        FutureTypeAdapter<T> ongoingCall = (FutureTypeAdapter<T>) threadCalls.get(type);
        if (ongoingCall != null) {
            return ongoingCall;
        }
        try {
            //创建一个FutureTypeAdapter对象
            FutureTypeAdapter<T> call = new FutureTypeAdapter<T>();
            //将其缓存
            threadCalls.put(type, call);
            for (TypeAdapterFactory factory : factories) {
                //通过注册的factory来创建type类型，如果创建成功，则表明factory能够进行type类型的创建，
                //如果返回未null，则表明factory不能进行type类型的创建
                TypeAdapter<T> candidate = factory.create(this, type);
                if (candidate != null) {
                    call.setDelegate(candidate);
                    //创建成功则进行缓存
                    typeTokenCache.put(type, candidate);
                    return candidate;
                }
            }
            throw new IllegalArgumentException("GSON (" + GsonBuildConfig.VERSION + ") cannot handle " + type);
        } finally {
            //因为已经将type缓存到typeTokenCache中了。所以ThreadLocal里面的缓存用不到了。移除
            threadCalls.remove(type);
            //如果创建了ThreadLocal对象，则进行清除
            if (requiresThreadLocalCleanup) {
                calls.remove();
            }
        }
    }

    /**
     * This method is used to get an alternate type adapter for the specified type. This is used
     * to access a type adapter that is overridden by a {@link TypeAdapterFactory} that you
     * may have registered. This features is typically used when you want to register a type
     * adapter that does a little bit of work but then delegates further processing to the Gson
     * default type adapter. Here is an example:
     * <p>Let's say we want to write a type adapter that counts the number of objects being read
     * from or written to JSON. We can achieve this by writing a type adapter factory that uses
     * the <code>getDelegateAdapter</code> method:
     * <pre> {@code
     *  class StatsTypeAdapterFactory implements TypeAdapterFactory {
     *    public int numReads = 0;
     *    public int numWrites = 0;
     *    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
     *      final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
     *      return new TypeAdapter<T>() {
     *        public void write(JsonWriter out, T value) throws IOException {
     *          ++numWrites;
     *          delegate.write(out, value);
     *        }
     *        public T read(JsonReader in) throws IOException {
     *          ++numReads;
     *          return delegate.read(in);
     *        }
     *      };
     *    }
     *  }
     *  } </pre>
     * This factory can now be used like this:
     * <pre> {@code
     *  StatsTypeAdapterFactory stats = new StatsTypeAdapterFactory();
     *  Gson gson = new GsonBuilder().registerTypeAdapterFactory(stats).create();
     *  // Call gson.toJson() and fromJson methods on objects
     *  System.out.println("Num JSON reads" + stats.numReads);
     *  System.out.println("Num JSON writes" + stats.numWrites);
     *  }</pre>
     * Note that this call will skip all factories registered before {@code skipPast}. In case of
     * multiple TypeAdapterFactories registered it is up to the caller of this function to insure
     * that the order of registration does not prevent this method from reaching a factory they
     * would expect to reply from this call.
     * Note that since you can not override type adapter factories for String and Java primitive
     * types, our stats factory will not count the number of String or primitives that will be
     * read or written.
     *
     * @param skipPast The type adapter factory that needs to be skipped while searching for
     *                 a matching type adapter. In most cases, you should just pass <i>this</i> (the type adapter
     *                 factory from where {@link #getDelegateAdapter} method is being invoked).
     * @param type     Type for which the delegate adapter is being searched for.
     * @since 2.2
     */
    public <T> TypeAdapter<T> getDelegateAdapter(TypeAdapterFactory skipPast, TypeToken<T> type) {
        // Hack. If the skipPast factory isn't registered, assume the factory is being requested via
        // our @JsonAdapter annotation.
        if (!factories.contains(skipPast)) {
            skipPast = jsonAdapterFactory;
        }

        boolean skipPastFound = false;
        for (TypeAdapterFactory factory : factories) {
            if (!skipPastFound) {
                if (factory == skipPast) {
                    skipPastFound = true;
                }
                continue;
            }

            TypeAdapter<T> candidate = factory.create(this, type);
            if (candidate != null) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("GSON cannot serialize " + type);
    }

    /**
     * Returns the type adapter for {@code} type.
     *
     * @throws IllegalArgumentException if this GSON cannot serialize and
     *                                  deserialize {@code type}.
     */
    public <T> TypeAdapter<T> getAdapter(Class<T> type) {
        return getAdapter(TypeToken.get(type));
    }

    /**
     * This method serializes the specified object into its equivalent representation as a tree of
     * {@link JsonElement}s. This method should be used when the specified object is not a generic
     * type. This method uses {@link Class#getClass()} to get the type for the specified object, but
     * the {@code getClass()} loses the generic type information because of the Type Erasure feature
     * of Java. Note that this method works fine if the any of the object fields are of generic type,
     * just the object itself should not be of a generic type. If the object is of generic type, use
     * {@link #toJsonTree(Object, Type)} instead.
     *
     * @param src the object for which Json representation is to be created setting for Gson
     * @return Json representation of {@code src}.
     * @since 1.4
     */
    public JsonElement toJsonTree(Object src) {
        if (src == null) {
            return JsonNull.INSTANCE;
        }
        return toJsonTree(src, src.getClass());
    }

    /**
     * This method serializes the specified object, including those of generic types, into its
     * equivalent representation as a tree of {@link JsonElement}s. This method must be used if the
     * specified object is a generic type. For non-generic objects, use {@link #toJsonTree(Object)}
     * instead.
     *
     * @param src       the object for which JSON representation is to be created
     * @param typeOfSrc The specific genericized type of src. You can obtain
     *                  this type by using the {@link com.google.gson.reflect.TypeToken} class. For example,
     *                  to get the type for {@code Collection<Foo>}, you should use:
     *                  <pre>
     *                  Type typeOfSrc = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                  </pre>
     * @return Json representation of {@code src}
     * @since 1.4
     */
    public JsonElement toJsonTree(Object src, Type typeOfSrc) {
        JsonTreeWriter writer = new JsonTreeWriter();
        toJson(src, typeOfSrc, writer);
        return writer.get();
    }

    /**
     * This method serializes the specified object into its equivalent Json representation.
     * This method should be used when the specified object is not a generic type. This method uses
     * {@link Class#getClass()} to get the type for the specified object, but the
     * {@code getClass()} loses the generic type information because of the Type Erasure feature
     * of Java. Note that this method works fine if the any of the object fields are of generic type,
     * just the object itself should not be of a generic type. If the object is of generic type, use
     * {@link #toJson(Object, Type)} instead. If you want to write out the object to a
     * {@link Writer}, use {@link #toJson(Object, Appendable)} instead.
     *
     * @param src the object for which Json representation is to be created setting for Gson
     * @return Json representation of {@code src}.
     */
    //将src对象转化为string
    public String toJson(Object src) {
        if (src == null) {
            return toJson(JsonNull.INSTANCE);
        }
        //调用重载方法
        return toJson(src, src.getClass());
    }

    /**
     * This method serializes the specified object, including those of generic types, into its
     * equivalent Json representation. This method must be used if the specified object is a generic
     * type. For non-generic objects, use {@link #toJson(Object)} instead. If you want to write out
     * the object to a {@link Appendable}, use {@link #toJson(Object, Type, Appendable)} instead.
     *
     * @param src       the object for which JSON representation is to be created
     * @param typeOfSrc The specific genericized type of src. You can obtain
     *                  this type by using the {@link com.google.gson.reflect.TypeToken} class. For example,
     *                  to get the type for {@code Collection<Foo>}, you should use:
     *                  <pre>
     *                  Type typeOfSrc = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                  </pre>
     * @return Json representation of {@code src}
     */
    public String toJson(Object src, Type typeOfSrc) {
        //创建了一个StringWriter对象
        StringWriter writer = new StringWriter();
        //重载方法
        toJson(src, typeOfSrc, writer);
        return writer.toString();
    }

    /**
     * This method serializes the specified object into its equivalent Json representation.
     * This method should be used when the specified object is not a generic type. This method uses
     * {@link Class#getClass()} to get the type for the specified object, but the
     * {@code getClass()} loses the generic type information because of the Type Erasure feature
     * of Java. Note that this method works fine if the any of the object fields are of generic type,
     * just the object itself should not be of a generic type. If the object is of generic type, use
     * {@link #toJson(Object, Type, Appendable)} instead.
     *
     * @param src    the object for which Json representation is to be created setting for Gson
     * @param writer Writer to which the Json representation needs to be written
     * @throws JsonIOException if there was a problem writing to the writer
     * @since 1.2
     */
    public void toJson(Object src, Appendable writer) throws JsonIOException {
        if (src != null) {
            toJson(src, src.getClass(), writer);
        } else {
            toJson(JsonNull.INSTANCE, writer);
        }
    }

    /**
     * This method serializes the specified object, including those of generic types, into its
     * equivalent Json representation. This method must be used if the specified object is a generic
     * type. For non-generic objects, use {@link #toJson(Object, Appendable)} instead.
     *
     * @param src       the object for which JSON representation is to be created
     * @param typeOfSrc The specific genericized type of src. You can obtain
     *                  this type by using the {@link com.google.gson.reflect.TypeToken} class. For example,
     *                  to get the type for {@code Collection<Foo>}, you should use:
     *                  <pre>
     *                  Type typeOfSrc = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                  </pre>
     * @param writer    Writer to which the Json representation of src needs to be written.
     * @throws JsonIOException if there was a problem writing to the writer
     * @since 1.2
     */
    public void toJson(Object src, Type typeOfSrc, Appendable writer) throws JsonIOException {
        try {
            //创建一个JsonWriter对象
            JsonWriter jsonWriter = newJsonWriter(Streams.writerForAppendable(writer));
            toJson(src, typeOfSrc, jsonWriter);
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }

    /**
     * Writes the JSON representation of {@code src} of type {@code typeOfSrc} to
     * {@code writer}.
     *
     * @throws JsonIOException if there was a problem writing to the writer
     */
    @SuppressWarnings("unchecked")
    public void toJson(Object src, Type typeOfSrc, JsonWriter writer) throws JsonIOException {
        //****重点方法****   获取对应的TypeAdapter
        TypeAdapter<?> adapter = getAdapter(TypeToken.get(typeOfSrc));
        //设置配置,并且保存原有的配置，在最后会将配置进行还原
        boolean oldLenient = writer.isLenient();
        writer.setLenient(true);
        boolean oldHtmlSafe = writer.isHtmlSafe();
        writer.setHtmlSafe(htmlSafe);
        boolean oldSerializeNulls = writer.getSerializeNulls();
        writer.setSerializeNulls(serializeNulls);
        try {
            //调用TypeAdapter里面的write方法
            ((TypeAdapter<Object>) adapter).write(writer, src);
        } catch (IOException e) {
            throw new JsonIOException(e);
        } catch (AssertionError e) {
            AssertionError error = new AssertionError("AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage());
            error.initCause(e);
            throw error;
        } finally {
            writer.setLenient(oldLenient);
            writer.setHtmlSafe(oldHtmlSafe);
            writer.setSerializeNulls(oldSerializeNulls);
        }
    }

    /**
     * Converts a tree of {@link JsonElement}s into its equivalent JSON representation.
     *
     * @param jsonElement root of a tree of {@link JsonElement}s
     * @return JSON String representation of the tree
     * @since 1.4
     */
    public String toJson(JsonElement jsonElement) {
        StringWriter writer = new StringWriter();
        toJson(jsonElement, writer);
        return writer.toString();
    }

    /**
     * Writes out the equivalent JSON for a tree of {@link JsonElement}s.
     *
     * @param jsonElement root of a tree of {@link JsonElement}s
     * @param writer      Writer to which the Json representation needs to be written
     * @throws JsonIOException if there was a problem writing to the writer
     * @since 1.4
     */
    public void toJson(JsonElement jsonElement, Appendable writer) throws JsonIOException {
        try {
            JsonWriter jsonWriter = newJsonWriter(Streams.writerForAppendable(writer));
            toJson(jsonElement, jsonWriter);
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }

    /**
     * Returns a new JSON writer configured for the settings on this Gson instance.
     */
    public JsonWriter newJsonWriter(Writer writer) throws IOException {
        if (generateNonExecutableJson) {
            writer.write(JSON_NON_EXECUTABLE_PREFIX);
        }
        JsonWriter jsonWriter = new JsonWriter(writer);
        if (prettyPrinting) {
            jsonWriter.setIndent("  ");
        }
        jsonWriter.setSerializeNulls(serializeNulls);
        return jsonWriter;
    }

    /**
     * Returns a new JSON reader configured for the settings on this Gson instance.
     */
    public JsonReader newJsonReader(Reader reader) {
        JsonReader jsonReader = new JsonReader(reader);
        jsonReader.setLenient(lenient);
        return jsonReader;
    }

    /**
     * Writes the JSON for {@code jsonElement} to {@code writer}.
     *
     * @throws JsonIOException if there was a problem writing to the writer
     */
    public void toJson(JsonElement jsonElement, JsonWriter writer) throws JsonIOException {
        boolean oldLenient = writer.isLenient();
        writer.setLenient(true);
        boolean oldHtmlSafe = writer.isHtmlSafe();
        writer.setHtmlSafe(htmlSafe);
        boolean oldSerializeNulls = writer.getSerializeNulls();
        writer.setSerializeNulls(serializeNulls);
        try {
            Streams.write(jsonElement, writer);
        } catch (IOException e) {
            throw new JsonIOException(e);
        } catch (AssertionError e) {
            AssertionError error = new AssertionError("AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage());
            error.initCause(e);
            throw error;
        } finally {
            writer.setLenient(oldLenient);
            writer.setHtmlSafe(oldHtmlSafe);
            writer.setSerializeNulls(oldSerializeNulls);
        }
    }

    /**
     * This method deserializes the specified Json into an object of the specified class. It is not
     * suitable to use if the specified class is a generic type since it will not have the generic
     * type information because of the Type Erasure feature of Java. Therefore, this method should not
     * be used if the desired type is a generic type. Note that this method works fine if the any of
     * the fields of the specified object are generics, just the object itself should not be a
     * generic type. For the cases when the object is of generic type, invoke
     * {@link #fromJson(String, Type)}. If you have the Json in a {@link Reader} instead of
     * a String, use {@link #fromJson(Reader, Class)} instead.
     *
     * @param <T>      the type of the desired object
     * @param json     the string from which the object is to be deserialized
     * @param classOfT the class of T
     * @return an object of type T from the string. Returns {@code null} if {@code json} is {@code null}
     * or if {@code json} is empty.
     * @throws JsonSyntaxException if json is not a valid representation for an object of type
     *                             classOfT
     */
    //将json字符串转化为T对象
    public <T> T fromJson(String json, Class<T> classOfT) throws JsonSyntaxException {
        //调用重载方法
        Object object = fromJson(json, (Type) classOfT);
        //如果解析以后，发现是int类型，会进行包装返回，也就是int->Integer
        return Primitives.wrap(classOfT).cast(object);
    }

    /**
     * This method deserializes the specified Json into an object of the specified type. This method
     * is useful if the specified object is a generic type. For non-generic objects, use
     * {@link #fromJson(String, Class)} instead. If you have the Json in a {@link Reader} instead of
     * a String, use {@link #fromJson(Reader, Type)} instead.
     *
     * @param <T>     the type of the desired object
     * @param json    the string from which the object is to be deserialized
     * @param typeOfT The specific genericized type of src. You can obtain this type by using the
     *                {@link com.google.gson.reflect.TypeToken} class. For example, to get the type for
     *                {@code Collection<Foo>}, you should use:
     *                <pre>
     *                Type typeOfT = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                </pre>
     * @return an object of type T from the string. Returns {@code null} if {@code json} is {@code null}
     * or if {@code json} is empty.
     * @throws JsonParseException  if json is not a valid representation for an object of type typeOfT
     * @throws JsonSyntaxException if json is not a valid representation for an object of type
     */
    @SuppressWarnings("unchecked")
    public <T> T fromJson(String json, Type typeOfT) throws JsonSyntaxException {
        //如果字符串是空，则直接返回null
        if (json == null) {
            return null;
        }
        //创建一个StringReader，入参是json字符串，
        StringReader reader = new StringReader(json);
        //重载方法
        T target = (T) fromJson(reader, typeOfT);
        return target;
    }

    /**
     * This method deserializes the Json read from the specified reader into an object of the
     * specified class. It is not suitable to use if the specified class is a generic type since it
     * will not have the generic type information because of the Type Erasure feature of Java.
     * Therefore, this method should not be used if the desired type is a generic type. Note that
     * this method works fine if the any of the fields of the specified object are generics, just the
     * object itself should not be a generic type. For the cases when the object is of generic type,
     * invoke {@link #fromJson(Reader, Type)}. If you have the Json in a String form instead of a
     * {@link Reader}, use {@link #fromJson(String, Class)} instead.
     *
     * @param <T>      the type of the desired object
     * @param json     the reader producing the Json from which the object is to be deserialized.
     * @param classOfT the class of T
     * @return an object of type T from the string. Returns {@code null} if {@code json} is at EOF.
     * @throws JsonIOException     if there was a problem reading from the Reader
     * @throws JsonSyntaxException if json is not a valid representation for an object of type
     * @since 1.2
     */
    public <T> T fromJson(Reader json, Class<T> classOfT) throws JsonSyntaxException, JsonIOException {
        JsonReader jsonReader = newJsonReader(json);
        Object object = fromJson(jsonReader, classOfT);
        assertFullConsumption(object, jsonReader);
        return Primitives.wrap(classOfT).cast(object);
    }

    /**
     * This method deserializes the Json read from the specified reader into an object of the
     * specified type. This method is useful if the specified object is a generic type. For
     * non-generic objects, use {@link #fromJson(Reader, Class)} instead. If you have the Json in a
     * String form instead of a {@link Reader}, use {@link #fromJson(String, Type)} instead.
     *
     * @param <T>     the type of the desired object
     * @param json    the reader producing Json from which the object is to be deserialized
     * @param typeOfT The specific genericized type of src. You can obtain this type by using the
     *                {@link com.google.gson.reflect.TypeToken} class. For example, to get the type for
     *                {@code Collection<Foo>}, you should use:
     *                <pre>
     *                Type typeOfT = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                </pre>
     * @return an object of type T from the json. Returns {@code null} if {@code json} is at EOF.
     * @throws JsonIOException     if there was a problem reading from the Reader
     * @throws JsonSyntaxException if json is not a valid representation for an object of type
     * @since 1.2
     */
    @SuppressWarnings("unchecked")
    public <T> T fromJson(Reader json, Type typeOfT) throws JsonIOException, JsonSyntaxException {
        //将Read进行包装，创建一个JsonReader
        JsonReader jsonReader = newJsonReader(json);
        //重载方法
        T object = (T) fromJson(jsonReader, typeOfT);
        //判断是否读取完了
        assertFullConsumption(object, jsonReader);
        return object;
    }

    private static void assertFullConsumption(Object obj, JsonReader reader) {
        try {
            if (obj != null && reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonIOException("JSON document was not fully consumed.");
            }
        } catch (MalformedJsonException e) {
            throw new JsonSyntaxException(e);
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }

    /**
     * Reads the next JSON value from {@code reader} and convert it to an object
     * of type {@code typeOfT}. Returns {@code null}, if the {@code reader} is at EOF.
     * Since Type is not parameterized by T, this method is type unsafe and should be used carefully
     *
     * @throws JsonIOException     if there was a problem writing to the Reader
     * @throws JsonSyntaxException if json is not a valid representation for an object of type
     */
    @SuppressWarnings("unchecked")
    public <T> T fromJson(JsonReader reader, Type typeOfT) throws JsonIOException, JsonSyntaxException {
        boolean isEmpty = true;
        boolean oldLenient = reader.isLenient();
        reader.setLenient(true);
        try {
            //返回下一个令牌的类型，但是并不使用它
            reader.peek();
            isEmpty = false;
            //将传入的class类型包装，生成一个TypeToken
            TypeToken<T> typeToken = (TypeToken<T>) TypeToken.get(typeOfT);
            //根据typeToken获取对应的TypeAdapter
            TypeAdapter<T> typeAdapter = getAdapter(typeToken);
            //通过typeAdapter转化对象
            T object = typeAdapter.read(reader);
            return object;
        } catch (EOFException e) {
            /*
             * For compatibility with JSON 1.5 and earlier, we return null for empty
             * documents instead of throwing.
             */
            if (isEmpty) {
                return null;
            }
            throw new JsonSyntaxException(e);
        } catch (IllegalStateException e) {
            throw new JsonSyntaxException(e);
        } catch (IOException e) {
            // TODO(inder): Figure out whether it is indeed right to rethrow this as JsonSyntaxException
            throw new JsonSyntaxException(e);
        } catch (AssertionError e) {
            AssertionError error = new AssertionError("AssertionError (GSON " + GsonBuildConfig.VERSION + "): " + e.getMessage());
            error.initCause(e);
            throw error;
        } finally {
            reader.setLenient(oldLenient);
        }
    }

    /**
     * This method deserializes the Json read from the specified parse tree into an object of the
     * specified type. It is not suitable to use if the specified class is a generic type since it
     * will not have the generic type information because of the Type Erasure feature of Java.
     * Therefore, this method should not be used if the desired type is a generic type. Note that
     * this method works fine if the any of the fields of the specified object are generics, just the
     * object itself should not be a generic type. For the cases when the object is of generic type,
     * invoke {@link #fromJson(JsonElement, Type)}.
     *
     * @param <T>      the type of the desired object
     * @param json     the root of the parse tree of {@link JsonElement}s from which the object is to
     *                 be deserialized
     * @param classOfT The class of T
     * @return an object of type T from the json. Returns {@code null} if {@code json} is {@code null}
     * or if {@code json} is empty.
     * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
     * @since 1.3
     */
    public <T> T fromJson(JsonElement json, Class<T> classOfT) throws JsonSyntaxException {
        Object object = fromJson(json, (Type) classOfT);
        return Primitives.wrap(classOfT).cast(object);
    }

    /**
     * This method deserializes the Json read from the specified parse tree into an object of the
     * specified type. This method is useful if the specified object is a generic type. For
     * non-generic objects, use {@link #fromJson(JsonElement, Class)} instead.
     *
     * @param <T>     the type of the desired object
     * @param json    the root of the parse tree of {@link JsonElement}s from which the object is to
     *                be deserialized
     * @param typeOfT The specific genericized type of src. You can obtain this type by using the
     *                {@link com.google.gson.reflect.TypeToken} class. For example, to get the type for
     *                {@code Collection<Foo>}, you should use:
     *                <pre>
     *                Type typeOfT = new TypeToken&lt;Collection&lt;Foo&gt;&gt;(){}.getType();
     *                </pre>
     * @return an object of type T from the json. Returns {@code null} if {@code json} is {@code null}
     * or if {@code json} is empty.
     * @throws JsonSyntaxException if json is not a valid representation for an object of type typeOfT
     * @since 1.3
     */
    @SuppressWarnings("unchecked")
    public <T> T fromJson(JsonElement json, Type typeOfT) throws JsonSyntaxException {
        if (json == null) {
            return null;
        }
        return (T) fromJson(new JsonTreeReader(json), typeOfT);
    }

    static class FutureTypeAdapter<T> extends TypeAdapter<T> {
        //代理模式
        private TypeAdapter<T> delegate;

        public void setDelegate(TypeAdapter<T> typeAdapter) {
            if (delegate != null) {
                throw new AssertionError();
            }
            delegate = typeAdapter;
        }

        @Override
        public T read(JsonReader in) throws IOException {
            if (delegate == null) {
                throw new IllegalStateException();
            }
            return delegate.read(in);
        }

        @Override
        public void write(JsonWriter out, T value) throws IOException {
            if (delegate == null) {
                throw new IllegalStateException();
            }
            delegate.write(out, value);
        }
    }

    @Override
    public String toString() {
        return new StringBuilder("{serializeNulls:")
                .append(serializeNulls)
                .append(",factories:").append(factories)
                .append(",instanceCreators:").append(constructorConstructor)
                .append("}")
                .toString();
    }
}
