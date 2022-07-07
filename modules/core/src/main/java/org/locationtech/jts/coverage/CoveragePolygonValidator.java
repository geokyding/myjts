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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.algorithm.locate.PointOnGeometryLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.LinearComponentExtracter;
import org.locationtech.jts.geom.util.PolygonExtracter;
import org.locationtech.jts.noding.MCIndexSegmentSetMutualIntersector;
import org.locationtech.jts.noding.SegmentString;


/**
 * Performs the following checks:
 * <ul>
 * <li>Exact duplicate polygons
 * <li>Misaligned segments
 * </ul>
 * 
 * @author mdavis
 *
 */
public class CoveragePolygonValidator {
  
  public static Geometry validate(Geometry base, Geometry adjPolygons) {
    return validate(base, adjPolygons, 0);
  }
  
  public static Geometry validate(Geometry target, Geometry adjPolygons, double distanceTolerance) {
    CoveragePolygonValidator v = new CoveragePolygonValidator(target);
    return v.validate(adjPolygons, distanceTolerance);
  }
  
  private Geometry targetGeom;
  private GeometryFactory geomFactory;
  private IndexedPointInAreaLocator[] adjPolygonLocators;

  public CoveragePolygonValidator(Geometry geom) {
    this.targetGeom = geom;
    geomFactory = targetGeom.getFactory();
  }
  
  public Geometry validate(Geometry adjGeoms, double distanceTolerance) {
    List<Polygon> adjPolygons = PolygonExtracter.getPolygons(adjGeoms);
    adjPolygonLocators = new IndexedPointInAreaLocator[adjPolygons.size()];
    
    //TODO: CANCEL skip non-touching polygons? (since adjacent one may be a legitimate MultiPolygon)
    //-- no, just use tol = 0 instead
    
    //TODO: DONE avoid flagging edges of spikes in coverage (perhaps: ignore matched edges in surrounding polygons?) 

    //TODO: DONE avoid flagging edges which match to test edges which are exact matches

    //TODO: DONE flag edges which are wholly inside target
    
    if (hasDuplicateGeom(targetGeom, adjGeoms)) {
      //TODO: convert to LineString copies
      return targetGeom.getBoundary();
    }
    
    List<CoverageRing> targetRings = extractRings(targetGeom);
    List<CoverageRing> adjRings = extractRings(adjGeoms);

    //System.out.println("# adj edges: " + adjSegStrings.size());
    Envelope targetEnv = targetGeom.getEnvelopeInternal().copy();
    targetEnv.expandBy(distanceTolerance);
    findMatchedSegments(targetRings, adjRings, targetEnv);

    //-- check if target is fully matched and thus forms a clean coverage 
    if (CoverageRing.isAllValid(targetRings))
      return createEmptyResult();
    
    findMisalignedSegments(targetRings, adjRings, distanceTolerance);
    findInteriorSegments(targetRings, adjPolygons);
    
    return createChains(targetRings);
  }

  private Geometry createEmptyResult() {
    return geomFactory.createLineString();
  }

  /**
   * Check if adjacent geoms contains a duplicate of the target.
   * This situation is not detected by segment alignment checking, since all segments are duplicate.

   * @param geom
   * @param adjGeoms 
   * @return
   */
  private boolean hasDuplicateGeom(Geometry geom, Geometry adjGeoms) {
    for (int i = 0; i < adjGeoms.getNumGeometries(); i++) {
      Geometry testGeom = adjGeoms.getGeometryN(i);
      if (testGeom.getEnvelopeInternal().equals(geom.getEnvelopeInternal())) {
        if (testGeom.equalsTopo(geom))
          return true;
      }
    }
    return false;
  }

  private void findMatchedSegments(List<CoverageRing> targetRings,
      List<CoverageRing> adjRngs, Envelope targetEnv) {
    Map<Segment, Segment> segmentMap = new HashMap<Segment, Segment>();
    addMatchedSegments(targetRings, targetEnv, segmentMap);
    addMatchedSegments(adjRngs, targetEnv, segmentMap);
  }
  
  /**
   * Adds polygon segments to the segment map, 
   * and detects if they match an existing segment.
   * In this case the segment is assumed to be coverage-valid.
   * 
   * @param rings
   * @param envLimit
   * @param segMap
   */
  private void addMatchedSegments(List<CoverageRing> rings, Envelope envLimit, 
      Map<Segment, Segment> segmentMap) {
    for (CoverageRing ring : rings) {
      for (int i = 0; i < ring.size() - 1; i++) {
        Segment seg = Segment.create(ring, i);
        //-- skip segments which lie outside the limit envelope
        if (! envLimit.intersects(seg.p0, seg.p1)) {
          continue;
        }
        if (segmentMap.containsKey(seg)) {
          Segment segMatch = segmentMap.get(seg);
          segMatch.markValid();
          seg.markValid();
        }
        else {
          segmentMap.put(seg, seg);
        }
      }
    }
  }

  private static class Segment extends LineSegment {
    public static Segment create(CoverageRing ring, int index) {
      Coordinate p0 = ring.getCoordinate(index);
      Coordinate p1 = ring.getCoordinate(index + 1);
      return new Segment(p0, p1, ring, index);
    }
    
    private CoverageRing ring;
    private int index;

    public Segment(Coordinate p0, Coordinate p1, CoverageRing ring, int index) {
      super(p0, p1);
      normalize();
      this.ring = ring;
      this.index = index;
    }
    
    public void markValid() {
      ring.markValid(index);
    }
  }
  
  //--------------------------------------------------
  
  
  private void findMisalignedSegments(List<CoverageRing> targetRings, List<CoverageRing> adjRings,
      double distanceTolerance) {
    InvalidSegmentDetector detector = new InvalidSegmentDetector(distanceTolerance);
    MCIndexSegmentSetMutualIntersector segSetMutInt = new MCIndexSegmentSetMutualIntersector(targetRings, distanceTolerance);
    segSetMutInt.process(adjRings, detector);
  }
  
  private void findInteriorSegments(List<CoverageRing> targetRings, List<Polygon> adjPolygons) {
    for (CoverageRing ring : targetRings) {
      for (int i = 0; i < ring.size() - 1; i++) {
        if (ring.isKnown(i))
          continue;
        
        /**
         * Check if vertex is in interior of an adjacent polygon.
         * If so, the segments on either side are in the interior.
         * Mark them invalid, unless they are already matched.
         */
        Coordinate p = ring.getCoordinate(i);
        if (isInteriorVertex(p, adjPolygons)) {
          ring.markInvalid(i);
          //-- previous segment may be interior (but may also be matched)
          int iPrev = i == 0 ? ring.size() - 2 : i-1;
          if (! ring.isKnown(iPrev))
            ring.markInvalid(iPrev);
        }
      }
    }
  }
  
  /**
   * Tests if a coordinate is in the interior of some adjacent polygon.
   * Uses the cached Point-In-Polygon indexed locators, for performance.
   * 
   * @param p the coordinate to test
   * @param adjPolygons the list of polygons
   * @return true if the point is in the interior
   */
  private boolean isInteriorVertex(Coordinate p, List<Polygon> adjPolygons) {
    /**
     * There should not be too many adjacent polygons, 
     * and hopefully not too many segments with unknown status
     * so a linear scan should not be too inefficient
     */
    //TODO: try a spatial index?
    for (int i = 0; i < adjPolygons.size(); i++) {
      Polygon adjPoly = adjPolygons.get(i);
      if (! adjPoly.getEnvelopeInternal().intersects(p))
        continue;
     
      if (polygonContainsPoint(i, adjPoly, p))
        return true;
    }
    return false;
  }

  private boolean polygonContainsPoint(int index, Polygon poly, Coordinate pt) {
    PointOnGeometryLocator pia = getLocator(index, poly);
    return Location.INTERIOR == pia.locate(pt);
  }

  private PointOnGeometryLocator getLocator(int index, Polygon poly) {
    IndexedPointInAreaLocator loc = adjPolygonLocators[index];
    if (loc == null) {
      loc = new IndexedPointInAreaLocator(poly);
      adjPolygonLocators[index] = loc;
    }
    return loc;
  }

  private Geometry createChains(List<CoverageRing> segStrings) {
    List<SegmentString> chains = new ArrayList<SegmentString>();
    for (CoverageRing ss : segStrings) {
      ss.createChains(chains);
    }
    
    if (chains.size() == 0) {
      return createEmptyResult();
    }
    
    LineString[] lines = new LineString[chains.size()];
    int i = 0;
    for (SegmentString ss : chains) {
      LineString line = geomFactory.createLineString(ss.getCoordinates());
      lines[i++] = line;
    }
    
    if (lines.length == 1) {
      return lines[0];
    }
    return geomFactory.createMultiLineString(lines);
  }  
  
  private static List<CoverageRing> extractRings(Geometry geom)
  {
    List<CoverageRing> segStr = new ArrayList<CoverageRing>();
    List<LineString> lines = LinearComponentExtracter.getLines(geom);
    for (LineString line : lines) {
      Coordinate[] pts = line.getCoordinates();
      segStr.add(new CoverageRing(pts, geom));
    }
    return segStr;
  }
}
