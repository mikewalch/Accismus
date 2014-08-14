/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.core.util;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;

import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.data.Span;
import io.fluo.core.impl.DelLockValue;
import io.fluo.core.impl.Environment;
import io.fluo.core.impl.TransactionImpl;
import io.fluo.core.impl.WriteValue;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;

/**
 * Utilities for modifying columns in Fluo
 */
public class ColumnUtil {
  
  public static final Bytes NOTIFY_CF = Bytes.wrap("ntfy");
  
  public static final long PREFIX_MASK = 0xe000000000000000l;
  public static final long TX_DONE_PREFIX = 0x6000000000000000l;
  public static final long WRITE_PREFIX = 0x4000000000000000l;
  public static final long DEL_LOCK_PREFIX = 0x2000000000000000l;
  public static final long LOCK_PREFIX = 0xe000000000000000l;
  public static final long ACK_PREFIX = 0xc000000000000000l;
  public static final long DATA_PREFIX = 0xa000000000000000l;
  
  public static final long TIMESTAMP_MASK = 0x1fffffffffffffffl;

  private ColumnUtil() {}

  public static byte[] concatCFCQ(Column c) {
    return Bytes.concat(c.getFamily(), c.getQualifier()).toArray();
  }

  public static void commitColumn(boolean isTrigger, boolean isPrimary, Column col, boolean isWrite, boolean isDelete, long startTs, long commitTs,
      Set<Column> observedColumns, Mutation m) {
    if (isWrite) {
      m.put(ByteUtil.toText(col.getFamily()), ByteUtil.toText(col.getQualifier()), col.getVisibilityParsed(), WRITE_PREFIX | commitTs, new Value(WriteValue.encode(startTs, isPrimary, false)));
    } else {
      m.put(ByteUtil.toText(col.getFamily()), ByteUtil.toText(col.getQualifier()), col.getVisibilityParsed(), DEL_LOCK_PREFIX | commitTs,
          new Value(DelLockValue.encode(startTs, isPrimary, false)));
    }
    
    if (isTrigger) {
      m.put(ByteUtil.toText(col.getFamily()), ByteUtil.toText(col.getQualifier()), col.getVisibilityParsed(), ACK_PREFIX | startTs, new Value(TransactionImpl.EMPTY));
      m.putDelete(NOTIFY_CF.toArray(), ColumnUtil.concatCFCQ(col), col.getVisibilityParsed(), startTs);
    }
    if (observedColumns.contains(col) && isWrite && !isDelete) {
      m.put(NOTIFY_CF.toArray(), ColumnUtil.concatCFCQ(col), col.getVisibilityParsed(), commitTs, TransactionImpl.EMPTY);
    }
  }
  
  public static Entry<Key,Value> checkColumn(Environment env, IteratorSetting iterConf, Bytes row, Column col) {
    Span span = Span.exact(row, col.getFamily(), col.getQualifier(), col.getVisibility());
    
    Scanner scanner;
    try {
      // TODO reuse or share scanner
      scanner = env.getConnector().createScanner(env.getTable(), env.getAuthorizations());
    } catch (TableNotFoundException e) {
      // TODO proper exception handling
      throw new RuntimeException(e);
    }
    scanner.setRange(SpanUtil.toRange(span));
    scanner.addScanIterator(iterConf);
    
    Iterator<Entry<Key,Value>> iter = scanner.iterator();
    if (iter.hasNext()) {
      Entry<Key,Value> entry = iter.next();
      
      Key k = entry.getKey();
      Bytes r = Bytes.wrap(k.getRowData().toArray());
      Bytes cf = Bytes.wrap(k.getColumnFamilyData().toArray());
      Bytes cq = Bytes.wrap(k.getColumnQualifierData().toArray());
      Bytes cv = Bytes.wrap(k.getColumnVisibilityData().toArray());
      
      if (r.equals(row) && cf.equals(col.getFamily()) && cq.equals(col.getQualifier())
          && cv.equals(col.getVisibility())) {
        return entry;
      } else {
        throw new RuntimeException("unexpected key " + k + " " + row + " " + col);
      }
    }
    
    return null;
  }
}