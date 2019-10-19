/*
 * Copyright (c) 2019 Martin Davis.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package test.jts.perf.index;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.SpatialIndex;
import org.locationtech.jts.index.hprtree.HPRtree;
import org.locationtech.jts.util.Stopwatch;

import test.jts.perf.PerformanceTestCase;
import test.jts.perf.PerformanceTestRunner;

public class HPRtreePerfTest
extends PerformanceTestCase {

  public static void main(String args[]) {
    PerformanceTestRunner.run(HPRtreePerfTest.class);
  }
  
  private HPRtree index;

  public HPRtreePerfTest(String name) {
    super(name);
    setRunSize(new int[] { 100, 10000, 1000000 });
    setRunIterations(1);
  }

  public void setUp()
  {
    
  }
  
  public void startRun(int size)
  {
    System.out.println("----- Tree size: " + size);
    
    index = new HPRtree();
    int side = (int) Math.sqrt(size);
    loadGrid(side, index);
    
    Stopwatch sw = new Stopwatch();
    index.build();
    System.out.println("Build time = " + sw.getTimeString());
  }

  private void loadGrid(int side, SpatialIndex index) {
    for (int i = 0; i < side; i++) {
      for (int j = 0; j < side; j++) {
        Envelope env = new Envelope(i, i+10, j, j+10);
        index.insert(env, i+"-"+j);
      }
    }
  }
  
  public void runQueries() {
    CountItemVisitor visitor = new CountItemVisitor();
    
    int size = index.size();
    int side = (int) Math.sqrt(size);
    side = 10;
    for (int i = 0; i < side; i++) {
      for (int j = 0; j < side; j++) {
        Envelope env = new Envelope(i, i+40, j, j+40);
        index.query(env, visitor);
        //System.out.println(visitor.count);
      }
    }

  }
}
