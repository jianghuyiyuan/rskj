/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.logfilter;

import org.ethereum.core.Bloom;
import org.ethereum.util.RLP;
import org.ethereum.util.RLPList;

import java.math.BigInteger;

/**
 * Created by ajlopez on 19/02/2020.
 */
public class BlocksBloomEncoder {
    private BlocksBloomEncoder() {

    }

    public static byte[] encode(BlocksBloom blocksBloom) {
        byte[] rlpFrom = encodeLong(blocksBloom.fromBlock());
        byte[] rlpTo = encodeLong(blocksBloom.toBlock());
        byte[] rlpData = RLP.encodeElement(blocksBloom.getBloom().getData());

        return RLP.encodeList(rlpFrom, rlpTo, rlpData);
    }

    public static BlocksBloom decode(byte[] data) {
        RLPList list = (RLPList) RLP.decode2(data).get(0);

        long from = decodeLong(list.get(0).getRLPData());
        long to = decodeLong(list.get(1).getRLPData());
        Bloom bloom = new Bloom(list.get(2).getRLPData());

        return new BlocksBloom(from, to, bloom);
    }

    private static byte[] encodeLong(long value) {
        if (value == -1) {
            return RLP.encodedEmptyByteArray();
        }

        return RLP.encodeBigInteger(BigInteger.valueOf(value));
    }

    private static long decodeLong(byte[] data) {
        if (data == null || data.length == 0) {
            return -1;
        }

        return RLP.decodeBigInteger(data, 0).longValue();
    }
}
