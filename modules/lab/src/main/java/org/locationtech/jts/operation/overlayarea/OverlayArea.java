/*
 * Copyright (c) 2020 Martin Davis
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jts.operation.overlayarea;

import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.algorithm.Area;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.algorithm.locate.PointOnGeometryLocator;
import org.locationtech.jts.algorithm.locate.SimplePointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFilter;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.kdtree.KdNode;
import org.locationtech.jts.index.kdtree.KdTree;
import org.locationtech.jts.math.MathUtil;
import org.locationtech.jts.noding.BasicSegmentString;
import org.locationtech.jts.noding.MCIndexSegmentSetMutualIntersector;
import org.locationtech.jts.noding.SegmentIntersector;
import org.locationtech.jts.noding.SegmentSetMutualIntersector;
import org.locationtech.jts.noding.SegmentString;

import java.util.Collections;
import java.util.List;

/**
 * Computes the area of the overlay of two polygons without forming
 * the actual topology of the overlay.
 * Since the topology is not needed, the computation is
 * is insensitive to the fine details of the overlay topology,
 * and hence is fully robust.
 * It also allows for a simpler implementation with more aggressive
 * performance optimization.
 * <p>
 * The algorithm uses mathematics derived from the work of William R. Franklin.
 * The area of a polygon can be computed as a sum of the partial areas
 * computed for each {@link EdgeVector} of the polygon.
 * This allows the area of the intersection of two polygons to be computed
 * by summing the partial areas for the edge vectors of the intersection resultant.
 * To determine the edge vectors all that is required 
 * is to compute the vertices of the intersection resultant, 
 * along with the direction (not the length) of the edges they belong to.
 * The resultant vertices are the vertices where the edges of the inputs intersect, 
 * along with the vertices of each input which lie in the interior of the other input.
 * The direction of the edge vectors is the same as the parent edges from which they derive.
 * Determining the vertices of intersection is simpler and more robust 
 * than determining the values of the actual edge line segments in the overlay result.
 * 
 * @author Martin Davis
 *
 */
public class OverlayArea {
  
  public static double intersectionArea(Geometry geom0, Geometry geom1) {
    if (! interacts(geom0, geom1))
      return 0;
    OverlayArea area = new OverlayArea(geom0);
    return area.intersectionArea(geom1);
  }
  
  private static boolean interacts(Geometry geom0, Geometry geom1) {
    return geom0.getEnvelopeInternal().intersects(geom1.getEnvelopeInternal());
  }

  private static LineIntersector li = new RobustLineIntersector();
  
  private Geometry geom0;
  private Envelope geomEnv0;
  private IndexedPointInAreaLocator locator0;
  private SegmentSetMutualIntersector segSetMutInt;
  private KdTree vertexIndex;

  public OverlayArea(Geometry geom) {
    this.geom0 = geom;
    
    //TODO: handle holes and multipolygons
    if (! (geom0 instanceof Polygon
        && ((Polygon) geom0).getNumInteriorRing() == 0))
      throw new IllegalArgumentException("Currently only Polygons with no holes supported");
    
    geomEnv0 = geom.getEnvelopeInternal();
    locator0 = new IndexedPointInAreaLocator(geom);
    segSetMutInt = buildSegmentIndex(geom);
    vertexIndex = buildVertexIndex(geom);
  }
  
  private boolean interacts(Geometry geom) {
    return geomEnv0.intersects(geom.getEnvelopeInternal());
  }
  
  public double intersectionArea(Geometry geom) {
    //-- intersection area is 0 if geom does not interact with geom0
    if (! interacts(geom)) return 0;

    PolygonAreaFilter filter = new PolygonAreaFilter();
    geom.apply(filter);
    return filter.area;
  }
  
  private class PolygonAreaFilter implements GeometryFilter {
    double area = 0;
    @Override
    public void filter(Geometry geom) {
      if (geom instanceof Polygon) {
        area += intersectionAreaPolygon((Polygon) geom);
      }
    }
  }
  
  private double intersectionAreaPolygon(Polygon geom) {
    //-- optimization - intersection area is 0 if geom does not interact with geom0
    if (! interacts(geom)) return 0;
    
    double area = 0;
    area += intersectionArea(geom.getExteriorRing());
    for (int i = 0; i < geom.getNumInteriorRing(); i++) {
      LinearRing hole = geom.getInteriorRingN(i);
      // skip holes which do not interact
      if (interacts(hole)) {
        area -= intersectionArea(hole);
      }
    }
    return area;
  }

  private double intersectionArea(LinearRing geom) {
    double areaInt = areaForIntersections(geom);
    
    /**
     * If area for segment intersections is zero then no segments intersect.
     * This means that either the geometries are disjoint, 
     * OR one is inside the other.
     * This allows computing the area efficiently
     * using a simple inside/outside test
     */
    if (areaInt == 0.0) {
      return areaContainedOrDisjoint(geom);
    }
    
    /**
     * The geometries intersect, so add areas for interior vertices
     */
    double areaVert1 = areaForInteriorVertices(geom);
    
    IndexedPointInAreaLocator locator1 = new IndexedPointInAreaLocator(geom);
    double areaVert0 = areaForInteriorVerticesIndexed(geom0, vertexIndex, geom.getEnvelopeInternal(), locator1);
    
    return (areaInt + areaVert1 + areaVert0) / 2;
  }

  /**
   * Computes the area for the situation where the geometries are known to either 
   * be disjoint, or have one contained in the other.
   * 
   * @param geom the other geometry to intersect
   * @return the area of the contained geometry, or 0.0 if disjoint
   */
  private double areaContainedOrDisjoint(LinearRing geom) {
    double area0 = areaForContainedGeom(geom, geom0.getEnvelopeInternal(), locator0);
    // if area is non-zero then geom is contained in geom0
    if (area0 != 0.0) return area0;
    
    // only checking one point, so non-indexed is faster
    SimplePointInAreaLocator locator = new SimplePointInAreaLocator(geom.getFactory().createPolygon(geom));
    double area1 = areaForContainedGeom(geom0, geom.getEnvelopeInternal(), locator);
    // geom0 is either disjoint or contained - either way we are done
    return area1;
  }

  /**
   * Tests and computes the area of a geometry contained in the other,
   * or 0.0 if the geometry is disjoint.
   * 
   * @param geom
   * @param env
   * @param locator
   * @return the area of the contained geometry, or 0 if it is disjoint
   */
  private double areaForContainedGeom(Geometry geom, Envelope env, PointOnGeometryLocator locator) {
    Coordinate pt = geom.getCoordinate();
    
    // fast check for disjoint
    if (! env.covers(pt)) return 0.0;
    // full check for contained
    if (Location.INTERIOR != locator.locate(pt)) return 0.0;
    
    return area(geom);
  }
  
  private static double area(Geometry geom) {
    if (geom instanceof LinearRing) {
      return Area.ofRing( ((LinearRing) geom).getCoordinateSequence() );
    }
    return geom.getArea();
  }

  private double areaForIntersections(LinearRing geom) {
    Coordinate[] coords = geom.getCoordinates();
    SegmentString segStr = new BasicSegmentString(coords, Orientation.isCCW(coords));

    IntersectionVisitor intVisitor = new IntersectionVisitor();
    segSetMutInt.process(Collections.singletonList(segStr), intVisitor);

    return intVisitor.getArea();
  }

  class IntersectionVisitor implements SegmentIntersector {
    private double area = 0.0;

    double getArea() {
      return area;
    }

    @Override
    public void processIntersections(SegmentString a, int aIndex, SegmentString b, int bIndex) {
      boolean isCCWA = (boolean) a.getData();
      boolean isCCWB = (boolean) b.getData();

      Coordinate a0 = a.getCoordinate(aIndex);
      Coordinate a1 = a.getCoordinate(aIndex + 1);
      Coordinate b0 = b.getCoordinate(bIndex);
      Coordinate b1 = b.getCoordinate(bIndex + 1);

      if (isCCWA) {
        Coordinate tmp = a0; a0 = a1; a1 = tmp;
      }
      if (isCCWB) {
          Coordinate tmp = b0; b0 = b1; b1 = tmp;
      }

      li.computeIntersection(a0, a1, b0, b1);
      if (! li.hasIntersection()) return;

      Coordinate intPt = li.getIntersection(0);

      if (li.isProper() || li.isInteriorIntersection()) {
        // Edge-edge intersection OR vertex-edge intersection

        /**
         * An intersection creates two edge vectors which contribute to the area.
         *
         * With both rings oriented CW (effectively)
         * There are two situations for segment intersection:
         *
         * 1) A entering B, B exiting A => rays are IP->A1:R, IP->B0:L
         * 2) A exiting B, B entering A => rays are IP->A0:L, IP->B1:R
         * (where IP is the intersection point,
         * and  :L/R indicates result polygon interior is to the Left or Right).
         *
         * For accuracy the full edge is used to provide the direction vector.
         */

        if (Orientation.CLOCKWISE == Orientation.index(a0, a1, b0)) {
          if (intPt.equals2D(a1)) {
            // Intersection at vertex and A0 -> A1 is outside the intersection area.
            // Area will be computed by the segment A1 -> A2
            return;
          }
          area += EdgeVector.area2Term(intPt, a0, a1, true);
          area += EdgeVector.area2Term(intPt, b1, b0, false);
        } else if (Orientation.CLOCKWISE == Orientation.index(a0, a1, b1)) {
          if (intPt.equals2D(a0)) {
            // Intersection at vertex and A0 -> A1 is outside the intersection area.
            // Area will be computed by the segment A(-1) -> A0
            return;
          }
          area += EdgeVector.area2Term(intPt, a1, a0, false);
          area += EdgeVector.area2Term(intPt, b0, b1, true);
        }

      } else {
        // vertex-vertex intersection
        // This intersection is visited 4 times - include only once
        if (!a1.equals2D(b1)) {
          return;
        }

        // If A0->A1 is collinear with B0->B1,
        // then the intersection point from LineIntersector might not be equal to A1 and B1
        intPt = a1;

        /* Get the next vertices in the CW direction.
        Now we have four segments: A0->A1, A1->A2, B0->B1, B1->B2
        and the intersection point is A1 == B1.
         */
        Coordinate a2 = a.nextInRing(aIndex + 1);
        Coordinate b2 = b.nextInRing(bIndex + 1);
        if (isCCWA) {
          a2 = a.prevInRing(aIndex);
        }
        if (isCCWB) {
          b2 = b.prevInRing(bIndex);
        }

        /* The angles A0->A1->A2 and B0->B1->B2 determine
         the maximum intersection area interior angle.
         Edges from the other polygon that lie within this angle
         are on the boundary of the intersection area.

         Depending on the relative orientation of the polygons,
         we could pick 0, 2 or 4 segments to contribute to the area.

        The LTE ja LT are chosen such that when A0->A1 is collinear with B0->B1,
        or when A1->A2 is collinear with B1->B2, then only the segment from polygon A
        is chosen to avoid double counting.
         */
        double aaAngle = Angle.interiorAngle(a0, intPt, a2);
        double bbAngle = Angle.interiorAngle(b0, intPt, b2);

        double abAngle = Angle.interiorAngle(a0, intPt, b2);
        double baAngle = Angle.interiorAngle(b0, intPt, a2);

        if (abAngle <= bbAngle) {
          area += EdgeVector.area2Term(intPt, a1, a0, false);
        }
        if (baAngle <= bbAngle) {
          area += EdgeVector.area2Term(intPt, a1, a2, true);
        }
        if (baAngle < aaAngle) {
          area += EdgeVector.area2Term(intPt, b1, b0, false);
        }
        if (abAngle < aaAngle) {
          area += EdgeVector.area2Term(intPt, b1, b2, true);
        }
      }
    }

    @Override
    public boolean isDone() {
      // Process all intersections
      return false;
    }
  }
  

  private double areaForInteriorVertices(LinearRing ring) {
    /**
     * Compute rays originating at vertices inside the intersection result
     * (i.e. A vertices inside B, and B vertices inside A)
     */
    double area = 0.0;
    CoordinateSequence seq = ring.getCoordinateSequence();
    boolean isCW = ! Orientation.isCCW(seq);
    
    for (int i = 0; i < seq.size()-1; i++) {
      Coordinate v = seq.getCoordinate(i);
      // quick bounda check
      if (! geomEnv0.contains(v)) continue;
      // is this vertex in interior of intersection result?
      if (Location.INTERIOR == locator0.locate(v)) {
        Coordinate vPrev = i == 0 ? seq.getCoordinate(seq.size()-2) : seq.getCoordinate(i-1);
        Coordinate vNext = seq.getCoordinate(i+1);
        area += EdgeVector.area2Term(v, vPrev, ! isCW)
            + EdgeVector.area2Term(v, vNext, isCW);
      }
    }
    return area;
  }
  
  private double areaForInteriorVerticesIndexed(Geometry geom, KdTree vertexIndex, Envelope env, IndexedPointInAreaLocator locator) {
    /**
     * Compute rays originating at vertices inside the intersection result
     * (i.e. A vertices inside B, and B vertices inside A)
     */
    double area = 0.0;
    CoordinateSequence seq = getVertices(geom);
    boolean isCW = ! Orientation.isCCW(seq);
    
    List<KdNode> verts = vertexIndex.query(env);
    for (KdNode kdNode : verts) {
      int i = (Integer) kdNode.getData();
      Coordinate v = seq.getCoordinate(i);
      // is this vertex in interior of intersection result?
      if (Location.INTERIOR == locator.locate(v)) {
        Coordinate vPrev = i == 0 ? seq.getCoordinate(seq.size()-2) : seq.getCoordinate(i-1);
        Coordinate vNext = seq.getCoordinate(i+1);
        area += EdgeVector.area2Term(v, vPrev, ! isCW)
            + EdgeVector.area2Term(v, vNext, isCW);
      }
    }
    return area;
  }
  
  private static CoordinateSequence getVertices(Geometry geom) {
    Polygon poly = (Polygon) geom;
    CoordinateSequence seq = poly.getExteriorRing().getCoordinateSequence();
    return seq;
  }
  
  private static SegmentSetMutualIntersector buildSegmentIndex(Geometry geom) {
    Coordinate[] coords = geom.getCoordinates();
    SegmentString segStr = new BasicSegmentString(coords, Orientation.isCCW(coords));
    return new MCIndexSegmentSetMutualIntersector(Collections.singletonList(segStr));
  }

  private static KdTree buildVertexIndex(Geometry geom) {
    Coordinate[] coords = geom.getCoordinates();
    KdTree index = new KdTree();
    //-- don't insert duplicate last vertex
    int[] ints = MathUtil.shuffle(coords.length - 1);
    //Arrays.sort(ints);
    for (int i : ints) {
      index.insert(coords[i], i);
    }
    //System.out.println("Depth = " + index.depth() +  " size = " + index.size());
    return index;
  }

}
