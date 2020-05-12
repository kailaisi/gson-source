/*
 * Copyright (C) 2010 Google Inc.
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

package com.google.gson.stream;

/**
 * Lexical scoping elements within a JSON reader or writer.
 *
 * @author Jesse Wilson
 * @since 1.6
 */
final class JsonScope {

    /**
     * An array with no elements requires no separators or newlines before
     * it is closed.
     */
    //没有元素的数组（相当于之前刚读了“[”），下一个元素一定不是逗号
    static final int EMPTY_ARRAY = 1;

    /**
     * A array with at least one value requires a comma and newline before
     * the next element.
     */
    //非空数组（至少已经有一个元素），下一个元素不是逗号就是“]”
    static final int NONEMPTY_ARRAY = 2;

    /**
     * An object with no name/value pairs requires no separators or newlines
     * before it is closed.
     */
    //没有元素的对象（相当于之前刚读了“{”），下一个元素一定不是逗号
    static final int EMPTY_OBJECT = 3;

    /**
     * An object whose most recent element is a key. The next element must
     * be a value.
     */
    //属性名，下一个元素一定是值。
    static final int DANGLING_NAME = 4;

    /**
     * An object with at least one name/value pair requires a comma and
     * newline before the next element.
     */
    //非空对象，至少一个name/value对），下一个元素不是逗号就是“}”
    static final int NONEMPTY_OBJECT = 5;

    /**
     * No object or array has been started.
     */
    //空文档，初识状态，啥也没读
    static final int EMPTY_DOCUMENT = 6;

    /**
     * A document with at an array or object.
     */
    //非空文档,文档中有一个顶级的数组/对象
    static final int NONEMPTY_DOCUMENT = 7;

    /**
     * A document that's been closed and cannot be accessed.
     */
    //文档已被关闭，不能再被访问
    static final int CLOSED = 8;
}
