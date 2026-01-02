/**
 * A Fractal Explorer created in Java
 * @author William Fiset, william.alexandre.fiset@gmail.com
 **/

import java.util.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import javax.swing.*;

public class FractalExplorer extends JFrame {

	static final int WIDTH  = 600;
	static final int HEIGHT = 600;
	static final int MAX_ITER = 200;

	static final double DEFAULT_ZOOM       = 100.0;
	static final double DEFAULT_TOP_LEFT_X = -3.0;
	static final double DEFAULT_TOP_LEFT_Y = +3.0;

	Canvas canvas;
	BufferedImage fractalImage;

	double zoomFactor = DEFAULT_ZOOM;
	double topLeftX   = DEFAULT_TOP_LEFT_X;
	double topLeftY   = DEFAULT_TOP_LEFT_Y;

	public FractalExplorer() {
		setInitialGUIProperties();
		addCanvas();
		canvas.addKeyStrokeEvents();
		updateFractal();
		this.setVisible(true);
	}

	public static void main(String[] args) {
		new FractalExplorer();
	}

	private void setInitialGUIProperties() {
		this.setTitle("Fractal Explorer");
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.setSize(WIDTH, HEIGHT);
		this.setResizable(false);
		this.setLocationRelativeTo(null);
	}

	private void addCanvas() {
		canvas = new Canvas();
		fractalImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		canvas.setVisible(true);
		this.add(canvas, BorderLayout.CENTER);
	}

	private double getXPos(double x) {
		return x/zoomFactor + topLeftX;
	}

	private double getYPos(double y) {
		return y/zoomFactor - topLeftY;
	}

	/**
	 * Updates the fractal by computing the number of iterations
	 * for each point in the fractal and changing the color
	 * based on that.
	 **/
	public void updateFractal() {
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				double c_r = getXPos(x);
				double c_i = getYPos(y);
				int iterCount = computeIterations(c_r, c_i);
				int pixelColor = makeColor(iterCount);
				fractalImage.setRGB(x, y, pixelColor);
			}
		}
		canvas.repaint();
	}

	/** Returns a posterized color based off of the iteration count
	    of a given point in the fractal **/
	private int makeColor(int iterCount) {
		int color = 0b011011100001100101101000;
		int mask  = 0b000000000000010101110111;
		int shiftMag = iterCount / 13;

		if (iterCount == MAX_ITER)
			return Color.BLACK.getRGB();

		return color | (mask << shiftMag);
	}


	/**
	 * Determines if a complex point c = c_r + c_i*i is in the Mandelbrot set.
	 *
	 * @param c_r the real part of the complex number c
	 * @param c_i the imaginary part of the complex number c
	 * @return number of iterations before |z| > 2, or MAX_ITER if point stays bounded
	 */
	private int computeIterations(double c_r, double c_i) {
		// z_r and z_i represent the real and imaginary parts of z = z_r + z_i*i
		// We start with z = 0 + 0i
		double z_r = 0.0;
		double z_i = 0.0;
		int iterCount = 0;

		// The Mandelbrot set definition: A point c is IN the set if repeatedly applying
		// the formula z(n+1) = z(n)² + c (starting with z(0) = 0) stays bounded.
		// "Stays bounded" means |z| never exceeds 2.0 (mathematically proven threshold).
		//
		// We iterate the formula and check if |z| = √(z_r² + z_i²) remains ≤ 2.0
		// Optimization: Compare z_r² + z_i² ≤ 4.0 to avoid computing the square root
		while (z_r*z_r + z_i*z_i <= 4.0) {
			// Apply z² + c using complex arithmetic:
			//   (z_r + z_i*i)² + (c_r + c_i*i) = (z_r² - z_i² + c_r) + (2*z_r*z_i + c_i)*i
			double z_r_tmp = z_r;
			z_r = z_r*z_r - z_i*z_i + c_r;  // Real part
			z_i = 2*z_i*z_r_tmp + c_i;      // Imaginary part

			// If still bounded after MAX_ITER iterations, consider the point IN the set
			if (iterCount >= MAX_ITER)
				return MAX_ITER;

			iterCount++;
		}

		// |z| exceeded 2.0, so the point is NOT in the Mandelbrot set
		// Return iteration count (used for coloring - faster escape = different color)
		return iterCount;
	}

	private void moveUp() {
		double curHeight = HEIGHT / zoomFactor;
		topLeftY += curHeight / 6;
		updateFractal();
	}

	private void moveDown() {
		double curHeight = HEIGHT / zoomFactor;
		topLeftY -= curHeight / 6;
		updateFractal();
	}

	private void moveLeft() {
		double curWidth = WIDTH / zoomFactor;
		topLeftX -= curWidth / 6;
		updateFractal();
	}

	private void moveRight() {
		double curWidth = WIDTH / zoomFactor;
		topLeftX += curWidth / 6;
		updateFractal();
	}

	private void adjustZoom(double newX, double newY, double newZoomFactor) {
		topLeftX += newX/zoomFactor;
		topLeftY -= newY/zoomFactor;

		zoomFactor = newZoomFactor;

		topLeftX -= (WIDTH/2) / zoomFactor;
		topLeftY += (HEIGHT/2) / zoomFactor;

		updateFractal();
	}


	private class Canvas extends JPanel implements MouseListener {

		public Canvas() {
			addMouseListener(this);
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(WIDTH, HEIGHT);
		}

		@Override
		public void paintComponent(Graphics drawingObj) {
			drawingObj.drawImage(fractalImage, 0, 0, null);
		}

		@Override
		public void mousePressed(MouseEvent mouse) {
			double x = (double) mouse.getX();
			double y = (double) mouse.getY();

			switch(mouse.getButton()) {
				case MouseEvent.BUTTON1: // Left click
					adjustZoom(x, y, zoomFactor*2);
					break;
				case MouseEvent.BUTTON3: // Right click
					adjustZoom(x, y, zoomFactor/2);
					break;
			}
		}
		

		public void addKeyStrokeEvents() {
			addKeyBinding(KeyEvent.VK_W, "w_key", e -> moveUp());
			addKeyBinding(KeyEvent.VK_A, "a_key", e -> moveLeft());
			addKeyBinding(KeyEvent.VK_S, "s_key", e -> moveDown());
			addKeyBinding(KeyEvent.VK_D, "d_key", e -> moveRight());
		}

		private void addKeyBinding(int keyCode, String actionKey, ActionListener action) {
			KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, 0);
			this.getInputMap().put(keyStroke, actionKey);
			this.getActionMap().put(actionKey, new AbstractAction() {
				@Override
				public void actionPerformed(ActionEvent e) {
					action.actionPerformed(e);
				}
			});
		}

		@Override public void mouseReleased(MouseEvent mouse) { }
		@Override public void mouseClicked(MouseEvent mouse) { }
		@Override public void mouseEntered(MouseEvent mouse) { }
		@Override public void mouseExited(MouseEvent mouse) { }
	}
}



















