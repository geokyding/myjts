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
package org.locationtech.jts.triangulate.polygon;

import java.util.List;

import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.noding.MCIndexNoder;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.SegmentIntersector;
import org.locationtech.jts.noding.SegmentString;
import org.locationtech.jts.noding.SegmentStringUtil;

/**
 * Adds node vertices to a polygon where holes touch the shell or each other.
 * The structure of the polygon is preserved.
 * This does not fix invalid polygon topology. Invalid input 
 * does not trigger an error, but remains invalid after noding.
 */
class PolygonNoder {

  /**
   * Adds node vertices to a polygon where holes touch the shell or each other.
   * The input is always copied, even if no nodes are added.
   * 
   * @param polygon the polygon to node
   * @return a fully-noded polygon
   */
  public static Polygon node(Polygon polygon) {
    PolygonNoder noder = new PolygonNoder(polygon);
    return noder.node();
  }

  private Polygon inputPolygon;
  private GeometryFactory geomFactory;

  private PolygonNoder(Polygon polygon) {
    inputPolygon = polygon;
    geomFactory = inputPolygon.getFactory();
  }
  
  private Polygon node() {
    //-- the shell is assumed to not self-intersect
    if (inputPolygon.getNumInteriorRing() == 0) {
      return (Polygon) inputPolygon.copy();
    }
    List<NodedSegmentString> segStrings = addNodes(inputPolygon);
    Polygon nodedPoly = buildPolygon(segStrings);
    return nodedPoly;
  }

  private List<NodedSegmentString> addNodes(Polygon polygon) {
    @SuppressWarnings("unchecked")
    List<NodedSegmentString> segStrings = SegmentStringUtil.extractNodedSegmentStrings(polygon);
    SegmentIntersector nodeAdder = new NodeAdder();
    MCIndexNoder noder = new MCIndexNoder(nodeAdder);
    noder.computeNodes(segStrings);
    return segStrings;
  }

  /**
   * A {@link SegmentIntersector} that added node vertices
   * to {@link NodedSegmentStrings} where a segment touches another
   * segment in its interior.
   * 
   * @author mdavis
   *
   */
  private static class NodeAdder implements SegmentIntersector {

    private LineIntersector li = new RobustLineIntersector();
    
    @Override
    public void processIntersections(SegmentString ss0, int segIndex0, SegmentString ss1, int segIndex1) {
      //-- input is assumed valid, so rings do not self-intersect
      if (ss0 == ss1)
        return;
      
      Coordinate p00 = ss0.getCoordinate(segIndex0);
      Coordinate p01 = ss0.getCoordinate(segIndex0 + 1);
      Coordinate p10 = ss1.getCoordinate(segIndex1);
      Coordinate p11 = ss1.getCoordinate(segIndex1 + 1);
      
      li.computeIntersection(p00, p01, p10, p11);
      if (li.getIntersectionNum() == 1) {
        Coordinate intPt = li.getIntersection(0);
        if (li.isInteriorIntersection(0)) {
          ((NodedSegmentString) ss0).addIntersectionNode(intPt, segIndex0);          
        }
        else if (li.isInteriorIntersection(1)) {
          ((NodedSegmentString) ss1).addIntersectionNode(intPt, segIndex1);          
        }
      }     
    }
    
    @Override
    public boolean isDone() {
      return false;
    }
  }
  
  private Polygon buildPolygon(List<NodedSegmentString> segStrings) {
    LinearRing shell = buildRing(segStrings.get(0));
    LinearRing[] holes = new LinearRing[segStrings.size() - 1];
    for (int i = 1; i < segStrings.size(); i++) {
      holes[i - 1] = buildRing(segStrings.get(i));
    }
    return geomFactory.createPolygon(shell, holes);
  }

  private LinearRing buildRing(NodedSegmentString segString) {
    Coordinate[] pts = segString.getNodedCoordinates();
    return geomFactory.createLinearRing(pts);
  }
}
