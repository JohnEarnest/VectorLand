import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.awt.geom.*;
import javax.swing.*;
import java.io.*;
import java.net.*;

public class VectorLand {
	static final Image buff = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);

	public static void main(String[] args) {
		View app = new View();
		app.setPreferredSize(new Dimension(640, 480));
		app.addMouseListener(app);
		app.addMouseMotionListener(app);

		JFrame window = new JFrame("VectorLand");
		window.add(app);
		window.addKeyListener(app);
		window.setResizable(false);
		window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		window.pack();
		window.setVisible(true);

		Renderer ren = new Renderer();
		ren.start();
		while(true) {
			app.repaint();

			if (app.out == null || ren.in == null) {
				try {
					Socket sock = new Socket(args[0], 10101);
					app.out = new DataOutputStream(sock.getOutputStream());
					ren.in  = new  DataInputStream(sock. getInputStream());
				}
				catch(IOException e) {
					try { Thread.sleep(5000); }
					catch(InterruptedException i) {}
				}
			}

			try { Thread.sleep(1000 / 30); }
			catch(InterruptedException e) {}
		}
	}
}

class View extends JPanel implements KeyListener, MouseListener, MouseMotionListener {
	DataOutputStream out;

	public void paint(Graphics g) {
		synchronized(VectorLand.buff) {
			g.drawImage(VectorLand.buff, 0, 0, null);
		}
	}

	void writeCommand(int command, int... args) {
		if (out == null) { return; }
		try {
			out.writeByte((byte)command);
			for(int a : args) { out.writeInt(a); }
			out.flush();
		}
		catch(IOException e) { out = null; }
	}

	public void keyPressed (KeyEvent e) { writeCommand(0, e.getKeyCode()); }
	public void keyReleased(KeyEvent e) { writeCommand(1, e.getKeyCode()); }
	public void keyTyped   (KeyEvent e) { writeCommand(2, e.getKeyChar()); }

	public void mousePressed(MouseEvent e)  { writeCommand(3); }
	public void mouseReleased(MouseEvent e) { writeCommand(4); }
	public void mouseClicked(MouseEvent e)  {}
	public void mouseEntered(MouseEvent e)  {}
	public void mouseExited(MouseEvent e)   {}

	public void mouseDragged(MouseEvent e) { writeCommand(5, e.getX(), e.getY()); }
	public void mouseMoved(MouseEvent e)   { writeCommand(5, e.getX(), e.getY()); }
}

class Renderer extends Thread {
	DataInputStream in;
	Color fill   = Color.BLACK;
	Color stroke = Color.BLACK;

	final Graphics2D g = (Graphics2D)VectorLand.buff.getGraphics();
	{ g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); }

	public void run() {
		while(true) {
			if (in == null) {
				synchronized(VectorLand.buff) {
					g.setColor(Color.WHITE);
					g.fillRect(0, 0, 640, 480);
					g.setColor(Color.BLACK);
					g.drawString("No connection to server.", 10, 20);
				}
				try { Thread.sleep(500); }
				catch(InterruptedException e) {}
			}
			else {
				try {
					in.readByte();
					synchronized(VectorLand.buff) {
						g.setColor(Color.WHITE);
						g.fillRect(0, 0, 640, 480);
						frame();
					}
				}
				catch(IOException e) { in = null; }
			}
		}
	}

	void frame() throws IOException {
		while(true) {
			switch(in.readByte()) {
				case 0:
					return;
				case 1:
					fill = readColor();
					break;
				case 2:
					stroke = readColor();
					break;
				case 3:
					int size = in.readInt()-1;
					Path2D.Float path = new Path2D.Float();
					path.moveTo(in.readShort(), in.readShort());
					while(size --> 0) {
						path.lineTo(in.readShort(), in.readShort());
					}
					path.closePath();
					g.setColor(fill);
					g.fill(path);
					g.setColor(stroke);
					g.draw(path);
			}
		}
	}

	Color readColor() throws IOException {
		return new Color(
			in.readByte() & 0xFF,
			in.readByte() & 0xFF,
			in.readByte() & 0xFF,
			in.readByte() & 0xFF
		);
	}
}