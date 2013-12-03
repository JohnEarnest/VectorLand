import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.event.KeyEvent;
import java.awt.Shape;
import java.awt.geom.*;

public class CTFServer {

	static final int PORT = 10101;
	static final int GOAL_FPS = 20;
	static final int MAX_SCORE = 5;

	static Set<Client>   clients = new HashSet<Client>();
	static Set<Path2D>     level = new HashSet<Path2D>();
	static Set<Spawner> spawners = new HashSet<Spawner>();
	static Set<Flag>       flags = new HashSet<Flag>();
	static List<String>    names = new ArrayList<String>();
	static int blue   = 0;
	static int orange = 0;
	static boolean done = false;
	static int donetimer = 0;

	static {
		names.addAll(Arrays.asList(
			"ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO",
			"FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT",
			"KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR",
			"PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO",
			"UNIFORM", "VICTOR", "WHISKEY", "XRAY", "YANKEE", "ZULU"
		));
		Collections.shuffle(names);
	}

	static {
		// central room
		level.add(box(   6,  -4,   3,  8));
		level.add(box( -20, -15,  17,  3));
		level.add(box( -20,  12,  17,  3));
		level.add(box( -20, -12,   3, 10));
		level.add(box( -20,   3,   3,  3));

		// outer walls
		level.add(box( -40, -30,  80,  3));
		level.add(box( -40, -27,   3, 54));

		// outer path obstacles
		level.add(box( -37,  -4,  10,  3));
		level.add(box(  -6, -22,   3,  7));

		new Spawner(10, 18);
		new Spawner(34,  8);

		new Flag(25, 20);

		Set<Path2D> copy = new HashSet<Path2D>(level);
		for(Path2D s : copy) {
			level.add((Path2D)s.createTransformedShape(AffineTransform.getScaleInstance(-1,-1)));
		}
	}

	public static void main(String[] args) throws IOException {
		Listener listener = new Listener(PORT);
		System.out.println("ready. waiting for clients...");
		listener.start();

		while(true) {
			tick();      // update the game state
			renderAll(); // send out a frame to all clients

			try { Thread.sleep(1000 / GOAL_FPS); }
			catch(InterruptedException e) {}
		}
	}

	static void tick() {
		Set<Client> targets = new HashSet<Client>();
		synchronized(clients) { targets.addAll(clients); }

		for(Client target : targets) {
			if (done) {
				if (donetimer == 0) {
					target.mode = Mode.Title;
					target.flag = null;
					target.kills = 0;
				}
			}
			else if (target.mode == Mode.Game) {
				// mouse aim
				target.angle = Math.atan2(
					(target.mouseY + (target.cy - 240)) - target.y,
					(target.mouseX + (target.cx - 320)) - target.x
				);

				// shooting
				if (target.clicktimer > 0) {
					target.clicktimer--;
					target.click = false;
				}
				else if (target.click) {
					target.clicktimer = 2;
					target.click = false;
					
					// calculate shot targets and kill shit
					double radius = raycast(target, targets);
					target.sx = target.x + radius * Math.cos(target.angle);
					target.sy = target.y + radius * Math.sin(target.angle);
					target.vx -= 7 * Math.cos(target.angle);
					target.vy -= 7 * Math.sin(target.angle);
				}

				// player movement
				synchronized(target.keys) {
					if (target.keys.contains(KeyEvent.VK_A)) { target.vx -= 1.80; }
					if (target.keys.contains(KeyEvent.VK_D)) { target.vx += 1.80; }
					if (target.keys.contains(KeyEvent.VK_W)) { target.vy -= 1.80; }
					if (target.keys.contains(KeyEvent.VK_S)) { target.vy += 1.80; }
				}

				for(Flag f : flags) {
					if (f.carried) { continue; }
					if (dist(target, f) >= 20) { continue; }
					if (f.orange == target.orange && !atBase(f)) {
						// return your flag by touching it
						f.x = f.bx;
						f.y = f.by;
					}
					else if (f.orange != target.orange && target.flag == null) {
						// capture enemy flag by coming close to it when you aren't carrying anything
						f.carried = true;
						target.flag = f;
					}
					else if (f.orange == target.orange && target.flag != null && atBase(f)) {
						// score by bringing enemy flag to your flag when it is at your base
						target.flag.carried = false;
						target.flag.x = target.flag.bx;
						target.flag.y = target.flag.by;
						target.flag = null;
						if (target.orange) { orange++; } else { blue++; }
						if (orange >= MAX_SCORE || blue >= MAX_SCORE) {
							done = true;
							donetimer = 300;
							for(Flag of : flags) {
								of.carried = false;
								of.x = of.bx;
								of.y = of.by;
							}
							break;
						}
					}
				}
			}
			else if (target.mode == Mode.Title || target.mode == Mode.Dead) {
				if (target.spawntimer > 0) {
					target.spawntimer--;
				}
				else if (target.click) {
					target.mode = Mode.Game;
					target.spawn();
				}
				target.click = false;
			}

			double ox = target.x;
			double oy = target.y;

			final int maxv = 15;
			target.vx = Math.min(maxv, Math.max(-maxv, target.vx));
			target.vy = Math.min(maxv, Math.max(-maxv, target.vy));
			target.x += target.vx;
			target.y += target.vy;
			target.vx *= .85;
			target.vy *= .85;

			// collision
			for(Shape obstacle : level) {
				if (obstacle.contains(target.x, target.y)) {
					target.x = ox;
					target.y = oy;
					target.vx *= -.8;
					target.vy *= -.8;
					target.bump = true;
				}
			}

			// camera tracking
			target.cx += (target.x - target.cx) / 10;
			target.cy += (target.y - target.cy) / 10;
		}
		if (done && donetimer > 0) { donetimer--; }
		else if (done) {
			done = false;
			orange = 0;
			blue = 0;
		}
	}

	static double raycast(Client c, Set<Client> targets) {
		int r = 10;
		while(r < 500) {
			double px = c.x + r * Math.cos(c.angle);
			double py = c.y + r * Math.sin(c.angle);
			for(Client o : targets) {
				if (o.orange == c.orange) { continue; }
				if (o.mode != Mode.Game) { continue; }
				if (dist(o, px, py) < 10) {
					o.mode = Mode.Dead;
					o.spawntimer = 30;
					c.kills++;
					if (o.flag != null) {
						o.flag.carried = false;
						o.flag.x = o.x;
						o.flag.y = o.y;
						o.flag = null;
					}
				}
			}
			for(Shape obstacle : level) {
				if (obstacle.contains(px, py)) { return r; }
			}
			r += 5;
		}
		return r;
	}

	static double dist(Client c, double x, double y) {
		double dx = c.x - x;
		double dy = c.y - y;
		return Math.sqrt(dx*dx + dy*dy);
	}
	static double dist(Client c, Flag f) {
		double dx = c.x - f.x;
		double dy = c.y - f.y;
		return Math.sqrt(dx*dx + dy*dy);
	}
	static boolean atBase(Flag f) {
		return f.x == f.bx && f.y == f.by && !f.carried;
	}

	static void renderAll() {
		Set<Client> targets = new HashSet<Client>();
		synchronized(clients) { targets.addAll(clients); }
		for(Client target : targets) {
			try { render(target, targets); }
			catch(IOException e) {
				System.out.format("client %s has disconnected.%n", target.name);
				synchronized(clients) { clients.remove(target); }
				synchronized(names) { names.add(target.name); }
			}
		}
	}

	static final Shape player; static {
		Path2D p = new Path2D.Float();
		p.moveTo(-10, -5);
		p.lineTo(  0,  5);
		p.lineTo( 10, -5);
		p.closePath();
		player = p.createTransformedShape(AffineTransform.getRotateInstance(-Math.PI/2));
	}

	// width and height are in segments of 10 units
	static Path2D box(int x, int y, int w, int h) {
		x *= 10;
		y *= 10;
		Path2D ret = new Path2D.Double();
		ret.moveTo(x, y);
		for(int z = 1; z <= w; z++) { ret.lineTo(x + z*10, y       ); }
		for(int z = 0; z <= h; z++) { ret.lineTo(x + w*10, y + z*10); }
		for(int z = w; z >= 0; z--) { ret.lineTo(x + z*10, y + h*10); }
		for(int z = h; z >  0; z--) { ret.lineTo(x       , y + z*10); }
		ret.closePath();
		return ret;
	}

	static void render(Client c, Set<Client> all) throws IOException {
		c.beginFrame();
		c.stroke(0xFF000000);

		c.relative = true;
		c.fill(0xFFAAAAAA);
		if (c.bump) {
			c.bump = false;
			c.wobble = 4;
		}
		for(Shape s : level) {
			c.poly(s);
		}
		for(Spawner s : spawners) {
			c.fill(s.orange ? 0xFFFFAA00 : 0xFFAAAAFF);
			c.poly(s.shape);
		}
		for(Flag f : flags) {
			c.center(VectorFont.text("X"), f.bx, f.by, 10);
			if (!f.carried) {
				c.fill(f.orange ? 0xFFFFAA00 : 0xFFAAAAFF);
				c.center(VectorFont.text("P"), f.x+10, f.y-10, 20);
			}
		}
		c.wobble = 0;

		for(Client a : all) {
			if (a.mode != Mode.Game) { continue; }
			
			if (a.clicktimer > 0) { c.fill(0xFFFFFFFF); }
			else { c.fill(a.orange ? 0xFFFFAA00 : 0xFFAAAAFF); }
			c.poly(player, a.x, a.y, a.angle, 1.0);

			if (a.flag != null) {
				c.fill(a.flag.orange ? 0xFFFFAA00 : 0xFFAAAAFF);
				c.center(VectorFont.text("P"), a.x+10, a.y-10, 20);
			}

			if (a != c) {
				c.fill(0x00FFFFFF);
				c.center(VectorFont.text(a.name), a.x, a.y - 20, 8);
			}
			if (a.clicktimer > 0) {
				c.line(
					a.sx,
					a.sy,
					a.x + 10 * Math.cos(a.angle),
					a.y + 10 * Math.sin(a.angle)
				);
			}
		}

		c.fill(0x00FFFFFF);
		if (done) {
			c.relative = false;
			c.wobble = 4;
			c.center(VectorFont.text((orange > blue ? "ORANGE" : "BLUE") + " TEAM WINS"), 320, 20, 40);
			c.wobble = 0;
			c.center(VectorFont.text("ORANGE " + orange), 160,   70, 20);
			c.center(VectorFont.text("BLUE "   +   blue), 160*3, 70, 20);
			
			int z1 = 0;
			for(Client a : all) {
				if (!a.orange) { continue; }
				c.center(VectorFont.text(a.name),     (640/5),   120+(z1*30), 20);
				c.center(VectorFont.text(""+a.kills), (640/5)*2, 120+(z1*30), 20);
				z1++;
			}
			int z2 = 0;
			for(Client a : all) {
				if (a.orange) { continue; }
				c.center(VectorFont.text(a.name),     (640/5)*3, 120+(z2*30), 20);
				c.center(VectorFont.text(""+a.kills), (640/5)*4, 120+(z2*30), 20);
				z2++;
			}

			c.center(VectorFont.text("WAIT " + (donetimer / 10)), 320, 440, 20);
		}
		else if (c.mode == Mode.Title) {
			c.relative = false;
			c.wobble = 6;
			c.center(VectorFont.text("VECTORLAND"), 320, 240, 60);
			c.wobble = 4;
			c.center(VectorFont.text("CAPTURE THE FLAG"), 320, 300, 40);
			c.wobble = 0;
			c.center(VectorFont.text("CLICK TO START"), 320, 440, 20);
		}
		else if (c.mode == Mode.Dead) {
			c.relative = false;
			c.wobble = 8;
			c.center(VectorFont.text("DEAD"), 320, 240, 70);
			c.wobble = 0;
			if (c.spawntimer > 0) {
				c.center(VectorFont.text("WAIT " + (c.spawntimer / 10)), 320, 440, 20);
			}
			else {
				c.center(VectorFont.text("CLICK TO RESPAWN"), 320, 440, 20);
			}
		}
		else {
			if (c.clicktimer == 0) {
				c.poly(
					c.x + 100 * Math.cos(c.angle),
					c.y + 100 * Math.sin(c.angle),
					c.x + 110 * Math.cos(c.angle),
					c.y + 110 * Math.sin(c.angle)
				);
			}
			c.relative = false;
			c.wobble = 0;
			c.center(VectorFont.text(c.name + " " + c.kills), 320, 10, 20);
			c.center(VectorFont.text("ORANGE " + orange), 160,   460, 20);
			c.center(VectorFont.text("BLUE "   + blue  ), 160*3, 460, 20);
		}

		c.endFrame();
	}
}

class Listener extends Thread {
	final ServerSocket s;

	Listener(int port) throws IOException {
		s = new ServerSocket(port);
	}

	public void run() {
		try {
			while(true) {
				Client c = new Client(s.accept());
				c.start();
			}
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}
}

enum Mode {
	Title,
	Game,
	Dead,
	End
}

class Client extends Thread {
	
	Mode mode = Mode.Title;
	String name = "BUTTS MCGEE";
	final boolean orange;

	Flag flag = null;
	int kills = 0;

	// player position and velocity
	double x  = 0;
	double y  = 0;
	double vx = 0;
	double vy = 0;

	// camera position
	double cx = x;
	double cy = y;

	boolean click = false;
	int clicktimer = 0;
	double sx = 0; // shot target x/y
	double sy = 0;

	int spawntimer = 0;

	boolean bump = false;
	boolean mouse = false;
	double angle;
	int mouseX = 320;
	int mouseY = 240;
	
	final DataOutputStream out;
	final DataInputStream in;
	final Set<Integer>     keys = new HashSet<Integer>();
	final Queue<Character> text = new LinkedList<Character>();

	Client(Socket s) throws IOException {
		out = new DataOutputStream(s.getOutputStream());
		in  = new DataInputStream(s.getInputStream());
		synchronized(CTFServer.names) {
			if (CTFServer.names.size() > 0) {
				name = CTFServer.names.remove(0);
			}
		}
		synchronized(CTFServer.clients) {
			int team = 0;
			for(Client c : CTFServer.clients) {
				if (c.orange) { team++; } else { team--; }
			}
			orange = team < 0;
			CTFServer.clients.add(this);
		}
		System.out.format("client %s has connected.%n", name);
	}

	public void run() {
		try {
			while(true) {
				byte op = in.readByte();
				switch(op) {
					case 0:
						int dn = in.readInt();
						synchronized(keys) { keys.add(dn); }
						break;
					case 1:
						int up = in.readInt();
						synchronized(keys) { keys.remove(up); }
						break;
					case 2:
						int c = in.readInt();
						synchronized(text) { text.add((char)c); }
						break;
					case 3:
						mouse = true;
						click = true;
						break;
					case 4:
						mouse = false;
						break;
					case 5:
						mouseX = in.readInt();
						mouseY = in.readInt();
						break;
					default:
						throw new Error("unknown input op " + op);
				}
			}
		}
		catch(IOException e) {
			System.out.println("client has disconnected.");
			if (flag != null) {
				flag.carried = false;
				flag.x = x;
				flag.y = y;
			}
			synchronized(CTFServer.clients) { CTFServer.clients.remove(this); }
		}
	}

	void spawn() {
		List<Spawner> spawners = new ArrayList<Spawner>(CTFServer.spawners);
		Collections.shuffle(spawners);
		while(spawners.get(0).orange != this.orange) { spawners.remove(0); }
		this.x = spawners.get(0).x;
		this.y = spawners.get(0).y;
		this.cx = x;
		this.cy = y;
	}

	/**
	* Drawing routines:
	**/

	double  wobble = 0;
	boolean fisheye = true;
	boolean relative = false;

	private void writePos(double x, double y) throws IOException {
		if (relative) {
			x -= (cx - 320);
			y -= (cy - 240);
		}
		if (wobble != 0) {
			x += ((Math.random()*2)-1)*wobble;
			y += ((Math.random()*2)-1)*wobble;
		}
		if (fisheye) {
			double tx = x - 320;
			double ty = y - 240;
			double r  = Math.sqrt(tx*tx + ty*ty);
			double a  = Math.atan2(ty, tx);
			double sz = 320 * 1.8;
			double hr = sz * Math.tanh(r / sz);
			x = 320 + hr * Math.cos(a);
			y = 240 + hr * Math.sin(a);
		}
		out.writeShort((short)x);
		out.writeShort((short)y);
	}

	private int recolor(int c) {
		return (c << 8) | (c >>> 24);
	}

	void fill(int color) throws IOException {
		color = recolor(color);
		out.writeByte(1);
		out.writeInt(color);
	}

	void stroke(int color) throws IOException {
		color = recolor(color);
		out.writeByte(2);
		out.writeInt(color);
	}

	void poly(double... verts) throws IOException {
		out.writeByte(3);
		out.writeInt(verts.length/2);
		for(int z = 0; z < verts.length; z += 2) {
			writePos(verts[z], verts[z+1]);
		}
	}

	void poly(Shape s, AffineTransform t) throws IOException {
		List<Double> verts = new ArrayList<Double>();
		PathIterator path = s.getPathIterator(t);
		double[] coords = new double[6];
		while(!path.isDone()) {
			path.currentSegment(coords);
			verts.add(coords[0]);
			verts.add(coords[1]);
			path.next();
		}
		out.writeByte(3);
		out.writeInt(verts.size()/2);
		for(int z = 0; z < verts.size(); z += 2) {
			writePos(verts.get(z), verts.get(z+1));
		}
	}

	void beginFrame() throws IOException {
		out.writeByte(0);
	}

	void endFrame() throws IOException {
		out.writeByte(0);
		out.flush();
	}

	/**
	* Additional helpers:
	**/

	void line(double x1, double y1, double x2, double y2) throws IOException {
		// in order for a fisheye distortion to draw a curved line,
		// we must construct it with additional segments.
		// (I'm not fucking around with bezier curves.)
		Path2D line = new Path2D.Double();
		line.moveTo(x1, y1);
		for(int z =  1; z <= 10; z++) { line.lineTo(lerp(x1, x2, z/10.0), lerp(y1, y2, z/10.0)); }
		for(int z = 10; z >=  0; z--) { line.lineTo(lerp(x1, x2, z/10.0), lerp(y1, y2, z/10.0)); }
		line.closePath();
		poly(line);
	}

	private double lerp(double a, double b, double t) {
		return a*(1-t) + b*(t);
	}

	void poly(Shape s) throws IOException {
		poly(s, new AffineTransform());
	}

	void poly(Shape s, double tx, double ty, double r, double scale) throws IOException {
		AffineTransform t = AffineTransform.getTranslateInstance(tx, ty);
		t.rotate(r);
		t.scale(scale, scale);
		poly(s, t);
	}

	void center(List<? extends Shape> shapes, double x, double y, double scale) throws IOException {
		for(int z = 0; z < shapes.size(); z++) {
			if(shapes.get(z) == null) { continue; }
			poly(shapes.get(z), x-(scale*shapes.size()/2)+(z*scale)+(scale/2), y, 0, scale/2.3);
		}
	}
}

class Spawner {
	final boolean orange;
	final int x;
	final int y;
	final Path2D shape;

	public Spawner(int x, int y) {
		this(x, y, true);
		CTFServer.spawners.add(this);
		CTFServer.spawners.add(new Spawner(-x, -y, false));
	}
	
	private Spawner(int x, int y, boolean orange) {
		this.orange = orange;
		this.x = x * 10;
		this.y = y * 10;
		this.shape = CTFServer.box(x-1, y-1, 2, 2);
	}
}

class Flag {
	final boolean orange;
	final double bx;
	final double by;

	double x = 0;
	double y = 0;
	boolean carried = false;

	public Flag(int x, int y) {
		this(x, y, true);
		CTFServer.flags.add(this);
		CTFServer.flags.add(new Flag(-x, -y, false));
	}

	private Flag(int x, int y, boolean orange) {
		this.bx = x * 10;
		this.by = y * 10;
		this.x = bx;
		this.y = by;
		this.orange = orange;
	}
}

class VectorFont {
	
	private static Path2D g(double x, double y, double... coords) {
		Path2D p = new Path2D.Float();
		p.moveTo(x, y);
		for(int z = 0; z < coords.length; z += 2) {
			p.lineTo(coords[z], coords[z+1]);
		}
		p.closePath();
		return p;
	}

	static Map<Character, Path2D> glyphs = new HashMap<Character, Path2D>();
	static {
		glyphs.put('A', g(0,-1, 1,1, .5,0, -.5,0, -1,1));
		glyphs.put('B', g(-1,-1, 0,-1, 0,0, -1,0, 1,0, 1,1, -1,1));
		glyphs.put('C', g(-1,-1, 1,-1, -1,-1, -1,1, 1,1, -1,1));
		glyphs.put('D', g(-1,-1, 0,-1, 1,0, 1,1, -1,1));
		glyphs.put('E', g(-1,-1, 1,-1, -1,-1, -1,0, 1,0, -1,0, -1,1, 1,1, -1,1));
		glyphs.put('F', g(-1,-1, 1,-1, -1,-1, -1,0, 1,0, -1,0, -1,1));
		glyphs.put('G', g(-1,-1, 1,-1, -1,-1, -1,1, 1,1, 1,0, 0,0, 1,0, 1,1, -1,1));
		glyphs.put('H', g(-1,-1, -1,0, 1,0, 1,-1, 1,1, 1,0, -1,0, -1,1));
		glyphs.put('I', g(-1,-1, 1,-1, 0,-1, 0,1, 1,1, -1,1, 0,1, 0,-1));
		glyphs.put('J', g(0,-1, 1,-1, 1,1, -1,1, 1,1, 1,-1));
		glyphs.put('K', g(-1,-1, -1,0, 1,-1, -1,0, 1,1, -1,0, -1,1));
		glyphs.put('L', g(-1,-1, -1,1, 1,1, -1,1));
		glyphs.put('M', g(-1,-1, 0,0, 1,-1, 1,1, 1,-1, 0,0, -1,-1, -1,1));

		glyphs.put('N', g(-1,-1, 1,1, 1,-1, 1,1, -1,-1, -1,1));
		glyphs.put('O', g(-1,-1, 1,-1, 1,1, -1,1));
		glyphs.put('P', g(-1,-1, 1,-1, 1,0, -1,0, -1,1));
		glyphs.put('Q', g(-1,-1, 1,-1, 1,1, 0,0, 1,1, -1,1));
		glyphs.put('R', g(-1,-1, 1,-1, 1,0, -1,0, 1,1, -1,0, -1,1));
		glyphs.put('S', g(-1,-1, 1,-1, -1,-1, -1,0, 1,0, 1,1, -1,1, 1,1, 1,0, -1,0));
		glyphs.put('T', g(-1,-1, 1,-1, 0,-1, 0,1, 0,-1));
		glyphs.put('U', g(-1,-1, -1,1, 1,1, 1,-1, 1,1, -1,1));
		glyphs.put('V', g(-1,-1, 0,1, 1,-1, 0,1));
		glyphs.put('W', g(-1,-1, -1,1, 0,0, 1,1, 1,-1, 1,1, 0,0, -1,1));
		glyphs.put('X', g(-1,-1, 0,0, 1,-1, 0,0, 1,1, 0,0, -1,1, 0,0));
		glyphs.put('Y', g(-1,-1, 0,0, 1,-1, 0,0, 0,1, 0,0));
		glyphs.put('Z', g(-1,-1, 1,-1, -1,1, 1,1, -1,1, 1,-1));

		glyphs.put('0', g(-1,-1, 1,-1, -1,1, 1,-1, 1,1, -1,1));
		glyphs.put('1', g(-1,-1, 0,-1, 0,1, 1,1, -1,1, 0,1, 0,-1));
		glyphs.put('2', g(-1,-1, 1,-1, 1,0, -1,0, -1,1, 1,1, -1,1, -1,0, 1,0, 1,-1));
		glyphs.put('3', g(-1,-1, 1,-1, -1,0, 1,0, 1,1, -1,1, 1,1, 1,0, -1,0, 1,-1));
		glyphs.put('4', g(-1,-1, -1,0, 1,0, 1,-1, 1,1, 1,0, -1,0));
		glyphs.put('5', g(-1,-1, 1,-1, -1,-1, -1,0, 1,0, 0,1, -1,1, 0,1, 1,0, -1,0));
		glyphs.put('6', g(-1,-1, 1,-1, -1,-1, -1,0, 1,0, 1,1, -1,1));
		glyphs.put('7', g(-1,-1, 1,-1, 0,0, 0,1, 0,0, 1,-1));
		glyphs.put('8', g(-1,-1, 1,-1, 1,0, -1,0, 1,0, 1,1, -1,1));
		glyphs.put('9', g(-1,-1, 1,-1, 1,1, 1,0, -1,0));

		glyphs.put(null, g(-1,-1, 1,-1, 1,1, -1,1));
	}

	static List<Path2D> text(String text) {
		List<Path2D> ret = new ArrayList<Path2D>(text.length());
		for(char c : text.toCharArray()) {
			if (c == ' ') { ret.add(null); continue; }
			ret.add((Path2D)glyphs.get(glyphs.containsKey(c) ? c : null).clone());
		}
		return ret;
	}
}