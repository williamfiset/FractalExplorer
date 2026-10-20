/**
 * A Fractal Explorer created in Java
 * @author William Fiset, william.alexandre.fiset@gmail.com
 **/

import java.util.*;
import java.util.stream.IntStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.awt.event.*;
import javax.swing.*;

public class FractalExplorer extends JFrame {

	static final int WIDTH  = 1000;
	static final int HEIGHT = 1000;
	static final int BASE_MAX_ITER = 200;
	static final int ITER_PER_ZOOM_LEVEL = 100;

	// Fit a 6-unit-wide slice of the complex plane across the window
	static final double DEFAULT_ZOOM       = WIDTH / 6.0;
	static final double DEFAULT_TOP_LEFT_X = -3.0;
	static final double DEFAULT_TOP_LEFT_Y = +3.0;

	// Doubles hold about 16 significant digits. Once neighbouring pixels are
	// only a few rounding steps apart, the image turns to noise and then blocks,
	// so stop zooming in before pixels get closer together than this
	static final double MIN_PIXEL_SPACING = 8 * Math.ulp(1.0);

	// Zoom speed is measured in doublings per second; the slider works in tenths
	static final int MIN_ZOOM_SPEED     = 1;
	static final int MAX_ZOOM_SPEED     = 40;
	static final int DEFAULT_ZOOM_SPEED = 10;
	static final int ZOOM_TICK_MS       = 16;
	// Guards against a big jump in zoom if the UI thread ever stalls
	static final double MAX_ZOOM_STEP_SECONDS = 0.1;

	// Escaping past a large radius (instead of 2) makes the smooth coloring accurate
	static final double ESCAPE_RADIUS_SQ = 256.0 * 256.0;
	static final double LOG_2 = Math.log(2);

	// The palette is a smooth gradient through these colors, repeating in a loop
	static final int[] PALETTE_STOPS = { 0x6e1d7f, 0x6e1bee, 0x6ebfe8, 0x6eff68, 0xeef968, 0xff9968 };
	static final int PALETTE_SIZE = 1024;
	static final int[] PALETTE = buildPalette();
	// How far along the palette each unit of sqrt(iterations) moves
	static final double COLOR_CYCLES_PER_SQRT_ITER = 0.25;

	Canvas canvas;
	JSlider zoomSpeedSlider;
	JLabel zoomLabel;

	// The part of the complex plane on screen. Only replaced on the UI thread,
	// and read by the render thread to know what to draw next
	volatile View view = new View(DEFAULT_ZOOM, DEFAULT_TOP_LEFT_X, DEFAULT_TOP_LEFT_Y);
	// The most recently finished image; only touched on the UI thread
	Frame frame;
	final Object renderLock = new Object();
	// Screen pixels per window pixel, e.g. 2 on a Retina display
	double nativeScale = 1;

	public FractalExplorer() {
		setInitialGUIProperties();
		addCanvas();
		addControls();
		canvas.addKeyStrokeEvents();
		this.pack();
		nativeScale = canvas.getGraphicsConfiguration().getDefaultTransform().getScaleX();
		startRenderThread();
		setView(view);
		this.setLocationRelativeTo(null);
		this.setVisible(true);
	}

	public static void main(String[] args) {
		new FractalExplorer();
	}

	private void setInitialGUIProperties() {
		this.setTitle("Fractal Explorer");
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.setResizable(false);
	}

	private void addCanvas() {
		canvas = new Canvas();
		canvas.setVisible(true);
		this.add(canvas, BorderLayout.CENTER);
	}

	private void addControls() {
		zoomSpeedSlider = new JSlider(MIN_ZOOM_SPEED, MAX_ZOOM_SPEED, DEFAULT_ZOOM_SPEED);
		Hashtable<Integer, JLabel> labels = new Hashtable<>();
		labels.put(MIN_ZOOM_SPEED, new JLabel("Slow"));
		labels.put(MAX_ZOOM_SPEED, new JLabel("Fast"));
		zoomSpeedSlider.setLabelTable(labels);
		zoomSpeedSlider.setPaintLabels(true);
		// Keep keyboard focus off the slider so it doesn't swallow key presses
		zoomSpeedSlider.setFocusable(false);

		zoomLabel = new JLabel();
		zoomLabel.setPreferredSize(new Dimension(260, 20));

		JPanel controls = new JPanel(new BorderLayout());
		controls.add(new JLabel("  Zoom speed  "), BorderLayout.WEST);
		controls.add(zoomSpeedSlider, BorderLayout.CENTER);
		controls.add(zoomLabel, BorderLayout.EAST);
		this.add(controls, BorderLayout.SOUTH);
	}

	private double getZoomSpeed() {
		return zoomSpeedSlider.getValue() / 10.0;
	}

	/** An immutable snapshot of which part of the complex plane is on screen **/
	static final class View {
		final double zoomFactor, topLeftX, topLeftY;

		View(double zoomFactor, double topLeftX, double topLeftY) {
			this.zoomFactor = zoomFactor;
			this.topLeftX = topLeftX;
			this.topLeftY = topLeftY;
		}

		double getXPos(double x) {
			return x/zoomFactor + topLeftX;
		}

		double getYPos(double y) {
			return y/zoomFactor - topLeftY;
		}

		/** Points near the set's boundary take longer to escape the deeper we zoom,
		    so grow the iteration cap by a fixed amount for every doubling of zoom **/
		int computeMaxIter() {
			double zoomLevels = Math.log(zoomFactor / DEFAULT_ZOOM) / LOG_2;
			return BASE_MAX_ITER + (int) Math.max(0, ITER_PER_ZOOM_LEVEL * zoomLevels);
		}
	}

	/** A finished image together with the view it was rendered for **/
	static final class Frame {
		final View view;
		final BufferedImage image;
		// Image pixels per window pixel
		final double scale;

		Frame(View view, BufferedImage image, double scale) {
			this.view = view;
			this.image = image;
			this.scale = scale;
		}
	}

	/** Shows a new view right away (by stretching the last image) and asks
	    the render thread to draw it properly **/
	private void setView(View newView) {
		view = newView;
		synchronized (renderLock) {
			renderLock.notifyAll();
		}
		boolean atLimit = newView.zoomFactor >= getMaxZoomFactor();
		zoomLabel.setText(String.format("Zoom %.1e×%s  ", newView.zoomFactor / DEFAULT_ZOOM,
				atLimit ? " (precision limit)" : ""));
		canvas.repaint();
	}

	private double getMaxZoomFactor() {
		return 1 / (MIN_PIXEL_SPACING * nativeScale);
	}

	private void startRenderThread() {
		Thread renderThread = new Thread(this::renderLoop, "fractal-renderer");
		renderThread.setDaemon(true);
		renderThread.start();
	}

	/**
	 * Renders the latest view whenever it changes. A full-resolution pass always
	 * runs to completion so that new images keep arriving while the user zooms;
	 * once the view stops changing, a sharper pass at the screen's native
	 * resolution follows (it's abandoned if the view changes mid-way).
	 **/
	private void renderLoop() {
		View rendered = null;
		while (true) {
			View target;
			synchronized (renderLock) {
				while ((target = view) == rendered) {
					try {
						renderLock.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
			}
			rendered = target;

			publish(target, renderImage(target, 1, false), 1);
			if (nativeScale > 1 && view == target) {
				BufferedImage sharp = renderImage(target, nativeScale, true);
				if (sharp != null)
					publish(target, sharp, nativeScale);
			}
		}
	}

	private void publish(View renderedView, BufferedImage image, double scale) {
		Frame finished = new Frame(renderedView, image, scale);
		SwingUtilities.invokeLater(() -> {
			frame = finished;
			canvas.repaint();
		});
	}

	/**
	 * Draws the fractal by computing the number of iterations for each point
	 * and picking a color based on that. Rows are split across all CPU cores.
	 *
	 * @param scale image pixels per window pixel
	 * @param cancellable whether to give up (and return null) if the view changes
	 **/
	BufferedImage renderImage(View v, double scale, boolean cancellable) {
		int w = (int) Math.round(WIDTH * scale);
		int h = (int) Math.round(HEIGHT * scale);
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		int maxIter = v.computeMaxIter();

		IntStream.range(0, h).parallel().forEach(py -> {
			if (cancellable && view != v)
				return;
			double c_i = v.getYPos(py / scale);
			for (int px = 0; px < w; px++) {
				double c_r = v.getXPos(px / scale);
				pixels[py*w + px] = makeColor(computeIterations(c_r, c_i, maxIter));
			}
		});
		return (cancellable && view != v) ? null : image;
	}

	/** Builds a looping gradient by blending between neighbouring color stops **/
	private static int[] buildPalette() {
		int[] palette = new int[PALETTE_SIZE];
		int stops = PALETTE_STOPS.length;
		for (int i = 0; i < PALETTE_SIZE; i++) {
			double pos = (double) i * stops / PALETTE_SIZE;
			int from = PALETTE_STOPS[(int) pos];
			int to = PALETTE_STOPS[((int) pos + 1) % stops];
			double t = pos - (int) pos;
			int r = (int) Math.round(((from >> 16) & 0xff) * (1-t) + ((to >> 16) & 0xff) * t);
			int g = (int) Math.round(((from >> 8) & 0xff) * (1-t) + ((to >> 8) & 0xff) * t);
			int b = (int) Math.round((from & 0xff) * (1-t) + (to & 0xff) * t);
			palette[i] = (r << 16) | (g << 8) | b;
		}
		return palette;
	}

	/** Returns a smoothly blended color based off of the (fractional) iteration
	    count of a given point in the fractal. Using the square root means deep
	    zooms, where counts are in the thousands, don't cycle through the
	    palette so fast that neighbouring pixels look like random noise **/
	static int makeColor(double iterCount) {
		if (iterCount < 0)
			return 0; // Black: the point is in the set

		double cycles = Math.sqrt(iterCount) * COLOR_CYCLES_PER_SQRT_ITER;
		return PALETTE[(int) ((cycles - Math.floor(cycles)) * PALETTE_SIZE)];
	}

	/**
	 * Determines if a complex point c = c_r + c_i*i is in the Mandelbrot set.
	 *
	 * @param c_r the real part of the complex number c
	 * @param c_i the imaginary part of the complex number c
	 * @param maxIter how many iterations to try before deciding the point is in the set
	 * @return a smooth (fractional) count of iterations before z escapes,
	 *         or -1 if the point stays bounded
	 */
	static double computeIterations(double c_r, double c_i, int maxIter) {
		// z_r and z_i represent the real and imaginary parts of z = z_r + z_i*i
		// We start with z = 0 + 0i
		double z_r = 0.0;
		double z_i = 0.0;

		// The Mandelbrot set definition: A point c is IN the set if repeatedly applying
		// the formula z(n+1) = z(n)² + c (starting with z(0) = 0) stays bounded.
		// Once |z| exceeds 2.0 it is guaranteed to escape to infinity, so any larger
		// radius gives the same answer. We use 256, which lets us measure *how far*
		// past the radius z landed, and turn the whole-number iteration count into
		// a smooth one; that removes the hard bands between colors.
		//
		// Optimization: Compare z_r² + z_i² to the radius squared to avoid a square root
		for (int iterCount = 0; iterCount < maxIter; iterCount++) {
			double z_r2 = z_r*z_r;
			double z_i2 = z_i*z_i;

			if (z_r2 + z_i2 > ESCAPE_RADIUS_SQ) {
				// The point is NOT in the set. log(log|z|) shrinks by log(2) each
				// iteration once |z| is large, so it tells us the fraction to subtract
				double logAbsZ = Math.log(z_r2 + z_i2) / 2;
				return iterCount + 1 - Math.log(logAbsZ / LOG_2) / LOG_2;
			}

			// Apply z² + c using complex arithmetic:
			//   (z_r + z_i*i)² + (c_r + c_i*i) = (z_r² - z_i² + c_r) + (2*z_r*z_i + c_i)*i
			z_i = 2*z_r*z_i + c_i;      // Imaginary part
			z_r = z_r2 - z_i2 + c_r;    // Real part
		}

		// Still bounded after maxIter iterations, so consider the point IN the set
		return -1;
	}

	/** Shifts the view by a fraction of the screen's width and height **/
	private void pan(double fractionX, double fractionY) {
		View v = view;
		setView(new View(v.zoomFactor,
				v.topLeftX + fractionX * WIDTH / v.zoomFactor,
				v.topLeftY + fractionY * HEIGHT / v.zoomFactor));
	}

	private void moveUp()    { pan(0, +1.0/6); }
	private void moveDown()  { pan(0, -1.0/6); }
	private void moveLeft()  { pan(-1.0/6, 0); }
	private void moveRight() { pan(+1.0/6, 0); }

	/** Zooms by the given factor while keeping the point under
	    the screen position (x, y) fixed in place **/
	private void zoomTowards(double x, double y, double factor) {
		View v = view;
		double newZoomFactor = Math.min(v.zoomFactor * factor, getMaxZoomFactor());
		if (newZoomFactor == v.zoomFactor)
			return;

		setView(new View(newZoomFactor,
				v.topLeftX + x/v.zoomFactor - x/newZoomFactor,
				v.topLeftY + y/newZoomFactor - y/v.zoomFactor));
	}


	private class Canvas extends JPanel implements MouseListener, MouseMotionListener {

		// +1 while zooming in, -1 while zooming out
		int zoomDirection;
		double mouseX, mouseY;
		long lastZoomTickNanos;
		javax.swing.Timer zoomTimer = new javax.swing.Timer(ZOOM_TICK_MS, e -> zoomTick());

		public Canvas() {
			addMouseListener(this);
			addMouseMotionListener(this);
		}

		/** Applies the zoom for the time elapsed since the last tick, so the
		    speed stays the same however often the timer manages to fire **/
		private void zoomTick() {
			long now = System.nanoTime();
			double elapsedSeconds = Math.min((now - lastZoomTickNanos) / 1e9, MAX_ZOOM_STEP_SECONDS);
			lastZoomTickNanos = now;

			double factor = Math.pow(2, zoomDirection * getZoomSpeed() * elapsedSeconds);
			zoomTowards(mouseX, mouseY, factor);
		}

		@Override
		public Dimension getPreferredSize() {
			// Qualify these: JPanel inherits ImageObserver.WIDTH/HEIGHT (1 and 2),
			// which would otherwise shadow the outer class's constants
			return new Dimension(FractalExplorer.WIDTH, FractalExplorer.HEIGHT);
		}

		/** Draws the latest finished image, stretched and shifted to line up with
		    the current view. While a new image renders, this keeps zooming and
		    panning instant **/
		@Override
		public void paintComponent(Graphics drawingObj) {
			Graphics2D g = (Graphics2D) drawingObj;
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, getWidth(), getHeight());
			if (frame == null)
				return;

			View current = view;
			View drawn = frame.view;
			double stretch = current.zoomFactor / drawn.zoomFactor;
			AffineTransform transform = new AffineTransform();
			transform.translate((drawn.topLeftX - current.topLeftX) * current.zoomFactor,
					(current.topLeftY - drawn.topLeftY) * current.zoomFactor);
			transform.scale(stretch / frame.scale, stretch / frame.scale);

			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(frame.image, transform, null);
		}

		@Override
		public void mousePressed(MouseEvent mouse) {
			switch(mouse.getButton()) {
				case MouseEvent.BUTTON1: // Hold left button to zoom in
					zoomDirection = +1;
					break;
				case MouseEvent.BUTTON3: // Hold right button to zoom out
					zoomDirection = -1;
					break;
				default:
					return;
			}
			mouseX = mouse.getX();
			mouseY = mouse.getY();
			lastZoomTickNanos = System.nanoTime();
			zoomTimer.start();
		}

		@Override
		public void mouseReleased(MouseEvent mouse) {
			zoomTimer.stop();
		}

		// Moving the mouse while holding a button steers the zoom
		@Override
		public void mouseDragged(MouseEvent mouse) {
			mouseX = mouse.getX();
			mouseY = mouse.getY();
		}


		public void addKeyStrokeEvents() {
			addKeyBinding(KeyEvent.VK_W, "w_key", e -> moveUp());
			addKeyBinding(KeyEvent.VK_A, "a_key", e -> moveLeft());
			addKeyBinding(KeyEvent.VK_S, "s_key", e -> moveDown());
			addKeyBinding(KeyEvent.VK_D, "d_key", e -> moveRight());
		}

		private void addKeyBinding(int keyCode, String actionKey, ActionListener action) {
			KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, 0);
			this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, actionKey);
			this.getActionMap().put(actionKey, new AbstractAction() {
				@Override
				public void actionPerformed(ActionEvent e) {
					action.actionPerformed(e);
				}
			});
		}

		@Override public void mouseMoved(MouseEvent mouse) { }
		@Override public void mouseClicked(MouseEvent mouse) { }
		@Override public void mouseEntered(MouseEvent mouse) { }
		@Override public void mouseExited(MouseEvent mouse) { }
	}
}




















