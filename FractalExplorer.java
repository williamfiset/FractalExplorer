
/**

A Fractal Explorer created in Java
@author William Fiset

**/

import java.util.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import javax.swing.*;

public class FractalExplorer extends JFrame {
	
	static final int WIDTH  = 600;
	static final int HEIGHT = 600;
	
	Canvas canvas;
	BufferedImage fractalImage;
	
	static final int MAX_ITER = 200;
	
	public FractalExplorer() {
		setInitialGUIProperties();
		addCanvas();
	}

	private int computeIterations(double c_r, double c_i) {
		
		/*
		
		Let c = c_r + c_i
		Let z = z_r + z_i
		
		z' = z*z + c
		   = (z_r + z_i)(z_r + z_i) + c_r + c_i
			 = z_r² + 2*z_r*z_i - z_i² + c_r + c_i
			
			 z_r' = z_r² - z_i² + c_r
			 z_i' = 2*z_i*z_r + c_i
		
		*/

		double z_r = 0.0;
		double z_i = 0.0;
		
		int iterCount = 0;

		// Modulus (distance) formula:
		// √(a² + b²) <= 2.0
		// a² + b² <= 4.0
		while ( z_r*z_r + z_i*z_i <= 4.0 ) {
			
			double z_r_tmp = z_r;
			
			z_r = z_r*z_r - z_i*z_i + c_r;
			z_i = 2*z_i*z_r_tmp + c_i;
			
			// Point was inside the Mandelbrot set
			if (iterCount >= MAX_ITER) 
				return MAX_ITER;
			
			iterCount++;
			
		}
		
		// Complex point was outside Mandelbrot set
		return iterCount;
		
	} 
	
	private void addCanvas() {
		
		canvas = new Canvas();
		fractalImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		canvas.setVisible(true);
		this.add(canvas, BorderLayout.CENTER);
		
	}
	
	private void setInitialGUIProperties() {
		
		this.setTitle("Fractal Explorer");
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.setSize(WIDTH, HEIGHT);
		this.setResizable(false);
		this.setLocationRelativeTo(null);
		this.setVisible(true);
		
	}
	
	public static void main(String[] args) {
		new FractalExplorer();
	}
		
	private class Canvas extends JPanel {
		
		@Override public Dimension getPreferredSize() {
			return new Dimension(WIDTH, HEIGHT);
		}
		
		@Override public void paintComponent(Graphics drawingObj) {
			drawingObj.drawImage( fractalImage, 0, 0, null );
		}
		
	}
	
	
}
























