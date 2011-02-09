package fiji.plugin.trackmate.visualization;

import fiji.plugin.trackmate.Feature;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.SpotImp;
import fiji.util.gui.OverlayedImageCanvas;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.gui.StackWindow;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

public class HyperStackDisplayer extends SpotDisplayer implements MouseListener {

	private ImagePlus imp;
	private OverlayedImageCanvas canvas;
	private float[] calibration;
	private Feature currentFeature;
	private float featureMaxValue;
	private float featureMinValue;
	private Settings settings;
	private StackWindow window;
	private SpotOverlay spotOverlay;
	private TrackOverlay trackOverlay;
	
	/** The spot currently being edited, empty if no spot is being edited. */
	private Spot editedSpot;
	/** A mapping of the spots versus the color they must be drawn in. */
	private Map<Spot, Color> spotColor = new HashMap<Spot, Color>();
	
	/*
	 * CONSTRUCTORS
	 */
	
	public HyperStackDisplayer(final Settings settings) {
		this.radius = settings.segmenterSettings.expectedRadius;
		this.imp = settings.imp;
		this.calibration = new float[] { settings.dx, settings.dy, settings.dz };
		this.settings = settings;
	}
	
	/*
	 * PUBLIC METHODS
	 */

	@Override
	public void setDisplayTrackMode(TrackDisplayMode mode, int displayDepth) {
		super.setDisplayTrackMode(mode, displayDepth);
		trackOverlay.setDisplayTrackMode(mode, displayDepth);
		imp.updateAndDraw();
	}
	
	@Override
	public void highlightEdges(Set<DefaultWeightedEdge> edges) {
		trackOverlay.setHighlight(edges);
	}
		
	@Override
	public void highlightSpots(Collection<Spot> spots) {
		spotOverlay.setSpotSelection(spots);
		imp.updateAndDraw();				
	}

	@Override
	public void centerViewOn(Spot spot) {
		int frame = - 1;
		for(int i : spotsToShow.keySet()) {
			List<Spot> spotThisFrame = spotsToShow.get(i);
			if (spotThisFrame.contains(spot)) {
				frame = i;
				break;
			}
		}
		if (frame == -1)
			return;
		int z = Math.round(spot.getFeature(Feature.POSITION_Z) / calibration[2] ) + 1;
		imp.setPosition(1, z, frame+1);
	}
	
	@Override
	public void setTrackVisible(boolean trackVisible) {
		trackOverlay.setTrackVisisble(trackVisible);
		imp.updateAndDraw();
	}
	
	@Override
	public void setSpotVisible(boolean spotVisible) {
		spotOverlay.setSpotVisible(spotVisible);
		imp.updateAndDraw();
	}
	
	@Override
	public void render() {
		if (null == imp) {
			this.imp = NewImage.createByteImage("Empty", settings.width, settings.height, settings.nframes*settings.nslices, NewImage.FILL_BLACK);
			this.imp.setDimensions(1, settings.nslices, settings.nframes);
		}
		imp.setOpenAsHyperStack(true);
		canvas = new OverlayedImageCanvas(imp);
		window = new StackWindow(imp, canvas);
		window.show();
		spotOverlay = new SpotOverlay(imp, calibration, radius);
		trackOverlay = new TrackOverlay(imp, calibration);
		canvas.addOverlay(spotOverlay);
		canvas.addOverlay(trackOverlay);
		canvas.addMouseListener(this);	
		imp.updateAndDraw();
	}
	
	@Override
	public void setRadiusDisplayRatio(float ratio) {
		super.setRadiusDisplayRatio(ratio);
		spotOverlay.setRadius(ratio*radius);
		refresh();
	}
	
	@Override
	public void setColorByFeature(Feature feature) {
		currentFeature = feature;
		// Get min & max
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		Float val;
		for (int key : spots.keySet()) {
			for (Spot spot : spots.get(key)) {
				val = spot.getFeature(feature);
				if (null == val)
					continue;
				if (val > max) max = val;
				if (val < min) min = val;
			}
		}
		featureMinValue = min;
		featureMaxValue = max;
		prepareSpotOverlay();
		refresh();
	}
		
	@Override
	public void refresh() {
		imp.updateAndDraw();
	}
		
	@Override
	public void setTrackGraph(SimpleWeightedGraph<Spot, DefaultWeightedEdge> trackGraph) {
		super.setTrackGraph(trackGraph);
		trackOverlay.setTrackGraph(trackGraph, spotsToShow);
		trackOverlay.setTrackColor(trackColors);
		imp.updateAndDraw();
		
	}
	
	@Override
	public void setSpotsToShow(SpotCollection spotsToShow) {
		super.setSpotsToShow(spotsToShow);
		prepareSpotOverlay();
	}
	
	@Override
	public void clear() {
		canvas.clearOverlay();
	}	
	
	/*
	 * PRIVATE METHODS
	 */
	
	private final Spot getCLickLocation(final MouseEvent e) {
		final int ix = canvas.offScreenX(e.getX());
		final int iy =  canvas.offScreenX(e.getY());
		final float x = ix * calibration[0];
		final float y = iy * calibration[1];
		final float z = (imp.getSlice()-1) * calibration[2];
		return new SpotImp(new float[] {x, y, z});
	}
	
	private void prepareSpotOverlay() {
		if (null == spotsToShow)
			return;
		Float val;
		for(Spot spot : spotsToShow) {
			val = spot.getFeature(currentFeature);
			if (null == currentFeature || null == val)
				spotColor.put(spot, color);
			else
				spotColor.put(spot, colorMap.getPaint((val-featureMinValue)/(featureMaxValue-featureMinValue)) );
		}
		spotOverlay.setTarget(spotsToShow);
		spotOverlay.setTargettColor(spotColor);
	}
	
	@Override
	public void mouseClicked(MouseEvent e) {
		final Spot clickLocation = getCLickLocation(e);
		final int frame = imp.getFrame() - 1;		
		Spot target = spotsToShow.getClosestSpot(clickLocation, frame);
		
		// Check desired behavior
		switch (e.getClickCount()) {
		case 1: {
			// Change selection
			// only if we are nut currently editing
			if (null != editedSpot)
				return;
			final int addToSelectionMask = MouseEvent.SHIFT_DOWN_MASK;
			final int flag;
			if ((e.getModifiersEx() & addToSelectionMask) == addToSelectionMask) 
				flag = MODIFY_SELECTION_FLAG;
			else 
				flag = REPLACE_SELECTION_FLAG;
			spotSelectionChanged(target, frame, flag);
			break;
		}
		
		case 2: {
			// Edit spot
			
			// Empty current selection
			spotSelectionChanged(null, frame, REPLACE_SELECTION_FLAG);
			
			if (null == editedSpot) {
				// No spot is currently edited, we pick one to edit
				System.out.println("Entering edit mode");// DEBUG
				if (target.squareDistanceTo(clickLocation) > radius*radius) {
					// Create a new spot if not inside one
					target = clickLocation;
					System.out.println("Creating new spot");// DEBUG
				} else {
					System.out.println("Editing spot "+target.getName());// DEBUG
				}
				editedSpot = target;
				
			} else {
				// We leave editing mode
				System.out.println("Leaving edit mode");// DEBUG
				editedSpot = null;
			}
			spotOverlay.setEditedSpot(editedSpot);
			break;
		}
		}
	} 


	@Override
	public void mousePressed(MouseEvent e) {}


	@Override
	public void mouseReleased(MouseEvent e) {}


	@Override
	public void mouseEntered(MouseEvent e) {}


	@Override
	public void mouseExited(MouseEvent e) {}


}
