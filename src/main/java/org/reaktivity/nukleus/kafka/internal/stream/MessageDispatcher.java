/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.kafka.internal.stream;

import java.util.function.Function;

import org.agrona.DirectBuffer;

public interface MessageDispatcher
{
    int FLAGS_MATCHED = 0x01;
    int FLAGS_DELIVERED = 0x02 | FLAGS_MATCHED;
    int FLAGS_BLOCKED = 0x04 | FLAGS_MATCHED;

    static boolean matched(int result)
    {
        return (result & FLAGS_MATCHED) > 0;
    }

    static boolean delivered(int result)
    {
        return (result & FLAGS_DELIVERED) > 0;
    }

    static boolean blocked(int result)
    {
        return (result & FLAGS_BLOCKED) > 0;
    }

    int dispatch(
        int partition,
        long requestOffset,
        long messageOffset,
        DirectBuffer key,
        Function<DirectBuffer, DirectBuffer> supplyHeader,
        long timestamp,
        long traceId,
        DirectBuffer value);

    void flush(
        int partition,
        long requestOffset,
        long lastOffset);

}
