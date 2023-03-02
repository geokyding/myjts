/*
 * Copyright (c) 2022 Martin Davis.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jts.coverage;

import org.locationtech.jts.geom.Geometry;

import junit.textui.TestRunner;
import test.jts.GeometryTestCase;

public class CoverageSimplifierTest extends GeometryTestCase {
  public static void main(String args[]) {
    TestRunner.run(CoverageSimplifierTest.class);
  }
  
  public CoverageSimplifierTest(String name) {
    super(name);
  }
  
  public void testNoopSimple2() {
    checkNoop(readArray(
        "POLYGON ((100 100, 200 200, 300 100, 200 101, 100 100))",
        "POLYGON ((150 0, 100 100, 200 101, 300 100, 250 0, 150 0))" )
    );
  }

  public void testNoopSimple3() {
    checkNoop(readArray(
        "POLYGON ((100 300, 200 200, 100 200, 100 300))",
        "POLYGON ((100 200, 200 200, 200 100, 100 100, 100 200))",
        "POLYGON ((100 100, 200 100, 150 50, 100 100))" )
    );
  }

  public void testNoopHole() {
    checkNoop(readArray(
        "POLYGON ((10 90, 90 90, 90 10, 10 10, 10 90), (20 80, 80 80, 80 20, 20 20, 20 80))",
        "POLYGON ((80 20, 20 20, 20 80, 80 80, 80 20))" )
    );
  }

  public void testNoopMulti() {
    checkNoop(readArray(
        "MULTIPOLYGON (((10 10, 10 50, 50 50, 50 10, 10 10)), ((90 90, 90 50, 50 50, 50 90, 90 90)))",
        "MULTIPOLYGON (((10 90, 50 90, 50 50, 10 50, 10 90)), ((90 10, 50 10, 50 50, 90 50, 90 10)))" )
    );
  }

  //---------------------------------------------
  
  public void testSimple2() {
    checkResult(readArray(
        "POLYGON ((100 100, 200 200, 300 100, 200 101, 100 100))",
        "POLYGON ((150 0, 100 100, 200 101, 300 100, 250 0, 150 0))" ),
        10,
        readArray(
            "POLYGON ((100 100, 200 200, 300 100, 100 100))",
            "POLYGON ((150 0, 100 100, 300 100, 250 0, 150 0))" )
    );
  }

  public void testSingleRingNoCollapse() {
    checkResult(readArray(
        "POLYGON ((10 50, 60 90, 70 50, 60 10, 10 50))" ),
        100000,
        readArray(
            "POLYGON ((10 50, 60 90, 60 10, 10 50))" )
    );
  }

  /**
   * Checks that a polygon on the edge of the coverage does not collapse 
   * under maximal simplification
   */
  public void testMultiEdgeRingNoCollapse() {
    checkResult(readArray(
        "POLYGON ((50 250, 200 200, 180 170, 200 150, 50 50, 50 250))",
        "POLYGON ((200 200, 180 170, 200 150, 200 200))"),
        40,
        readArray(
            "POLYGON ((50 250, 200 200, 180 170, 200 150, 50 50, 50 250))",
            "POLYGON ((200 200, 180 170, 200 150, 200 200))")
    );
  }

  public void testFilledHole() {
    checkResult(readArray(
        "POLYGON ((20 30, 20 80, 60 50, 80 20, 50 20, 20 30))",
        "POLYGON ((10 90, 90 90, 90 10, 10 10, 10 90), (50 20, 20 30, 20 80, 60 50, 80 20, 50 20))" ),
        28,
        readArray(
            "POLYGON ((20 30, 20 80, 80 20, 50 20, 20 30))",
            "POLYGON ((10 10, 10 90, 90 90, 90 10, 10 10), (20 30, 50 20, 80 20, 20 80, 20 30))" )
    );
  }

  public void testTwoTouchingHoles() {
    checkResult(readArray(
            "POLYGON (( 0 0, 0 11, 19 11, 19 0, 0 0 ), ( 4 5, 12 5, 12 6, 10 6, 10 8, 9 8, 9 9, 7 9, 7 8, 6 8, 6 6, 4 6, 4 5 ), ( 12 6, 14 6, 14 9, 13 9, 13 7, 12 7, 12 6 ))",
            "POLYGON (( 12 6, 12 5, 4 5, 4 6, 6 6, 6 8, 7 8, 7 9, 9 9, 9 8, 10 8, 10 6, 12 6 ))",
            "POLYGON (( 12 6, 12 7, 13 7, 13 9, 14 9, 14 6, 12 6 ))"),
        1.0,
        readArray(
            "POLYGON (( 0 0, 0 11, 19 11, 19 0, 0 0 ), ( 12 6, 10 6, 10 8, 7 9, 6 6, 4 5, 12 5, 12 6 ), ( 12 6, 14 6, 14 9, 12 6 ))",
            "POLYGON (( 12 6, 10 6, 10 8, 7 9, 6 6, 4 5, 12 5, 12 6 ))",
            "POLYGON (( 12 6, 14 6, 14 9, 12 6 ))" )
    );
  }

  public void testHoleTouchingShell() {
    checkResultInner(readArray(
            "POLYGON ((200 300, 300 300, 300 100, 100 100, 100 300, 200 300), (170 220, 170 160, 200 140, 200 250, 170 220), (170 250, 200 250, 200 300, 170 250))",
            "POLYGON ((170 220, 200 250, 200 140, 170 160, 170 220))",
            "POLYGON ((170 250, 200 300, 200 250, 170 250))"),
        100.0,
        readArray(
            "POLYGON ((200 300, 300 300, 300 100, 100 100, 100 300, 200 300), (200 250, 170 160, 200 140, 200 250), (200 250, 200 300, 170 250, 200 250))",
            "POLYGON ((200 250, 170 160, 200 140, 200 250))",
            "POLYGON ((200 250, 200 300, 170 250, 200 250))" )
    );
  }

  //---------------------------------
  
  public void testInnerSimple() {
    checkResultInner(readArray(
        "POLYGON ((50 50, 50 150, 100 190, 100 200, 200 200, 160 150, 120 120, 90 80, 50 50))",
        "POLYGON ((100 0, 50 50, 90 80, 120 120, 160 150, 200 200, 250 100, 170 50, 100 0))" ),
        100,
        readArray(
            "POLYGON ((50 50, 50 150, 100 190, 100 200, 200 200, 50 50))",
            "POLYGON ((200 200, 50 50, 100 0, 170 50, 250 100, 200 200))" )
    );
    
  }
  //=================================


  private void checkNoop(Geometry[] input) {
    Geometry[] actual = CoverageSimplifier.simplify(input, 0);
    checkEqual(input, actual);
  }
  
  private void checkResult(Geometry[] input, double tolerance, Geometry[] expected) {
    Geometry[] actual = CoverageSimplifier.simplify(input, tolerance);
    checkEqual(expected, actual);
  }
  
  private void checkResultInner(Geometry[] input, double tolerance, Geometry[] expected) {
    Geometry[] actual = CoverageSimplifier.simplifyInner(input, tolerance);
    checkEqual(expected, actual);
  }
}
