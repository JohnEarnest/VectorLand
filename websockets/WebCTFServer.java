import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.event.KeyEvent;
import java.awt.Shape;
import java.awt.geom.*;

public class WebCTFServer {

	static final int PORT = 10106;
	static final int GOAL_FPS = 20;
	static final int MAX_SCORE = 5;
	static final int FLAG_DELAY = 20;

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

	static void log(String format, Object... args) {
		System.out.format("[%s]: %s%n", new Date(), String.format(format, args));
	}

	public static void main(String[] args) throws IOException {
		Listener listener = new Listener(PORT);
		log("ready. waiting for clients...");
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
						f.capturetimer = 0;
						f.carried = true;
						target.flag = f;
					}
					else if (f.orange == target.orange && target.flag != null && atBase(f)) {
						// score by bringing enemy flag to your flag when it is at your base
						f.capturetimer = FLAG_DELAY;
						target.flag.carried = false;
						target.flag.x = target.flag.bx;
						target.flag.y = target.flag.by;
						target.flag = null;
						if (target.orange) { orange++; } else { blue++; }
						if (orange >= MAX_SCORE || blue >= MAX_SCORE) {
							done = true;
							log("round complete, %s wins.", (orange>blue)? "orange" : "blue");
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

			if (target.killtimer > 0) { target.killtimer--; }
		}

		for(Flag f : flags) {
			if (f.capturetimer > 0) { f.capturetimer--; }
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
					c.killtimer = 6;
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
				WebCTFServer.log("client %s has disconnected.", target.name);
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
		c.wobble = 0;
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
			if (f.capturetimer > 0) {
				c.fill(0x00FFFFFF);
				c.ngon(f.bx, f.by,
					Client.easeQuart(FLAG_DELAY - f.capturetimer, FLAG_DELAY, 1, 300),
					9,
					f.capturetimer / 30.0 * Math.PI
				);
			}
		}
		c.wobble = 0;

		for(Client a : all) {
			if (a.mode != Mode.Game) { continue; }
			
			if (a.clicktimer > 0) { c.fill(0xFFFFFFFF); }
			else { c.fill(a.orange ? 0xFFFFAA00 : 0xFFAAAAFF); }
			c.poly(player, a.x, a.y, a.angle, 1.0 - (a.clicktimer * .2));

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
			c.center(VectorFont.text(c.name),     640/3,   10, 20);

			// calculate flag status for HUD routines:
			boolean orangeHeld = false;
			boolean   blueHeld = false;
			int orangeflagtime = 0;
			int   blueflagtime = 0;
			for(Flag f : flags) {
				orangeHeld |=  f.orange && f.carried;
				  blueHeld |= !f.orange && f.carried;
				orangeflagtime = Math.max(orangeflagtime,  f.orange ? f.capturetimer-(FLAG_DELAY-7) : 0);
				  blueflagtime = Math.max(  blueflagtime, !f.orange ? f.capturetimer-(FLAG_DELAY-7) : 0);
			}

			double killscale = Math.sin(Math.PI * 2.0 / 6 * (6-c.killtimer));
			c.wobble = 3 * killscale;
			c.center(VectorFont.text(""+c.kills), 640/3*2, 10, 20 + (20 * killscale));

			double orangescale = Math.sin(Math.PI * 2.0 / 7 * (7-orangeflagtime));
			c.wobble = (orangeHeld ? 3 : 0) + (3 * orangescale);
			c.center(VectorFont.text("ORANGE " + orange), 160,   460, 20 + (10 * orangescale));

			double   bluescale = Math.sin(Math.PI * 2.0 / 7 * (7-blueflagtime));
			c.wobble = (  blueHeld ? 3 : 0) + (3 *   bluescale);
			c.center(VectorFont.text("BLUE "   + blue  ), 160*3, 460, 20 + (10 * bluescale));
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
	int killtimer = 0;

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
	
	final Set<Integer>     keys = new HashSet<Integer>();
	final Queue<Character> text = new LinkedList<Character>();
	final WebSocket sock;
	final ByteArrayOutputStream bout = new ByteArrayOutputStream();
	final DataOutputStream dout = new DataOutputStream(bout);

	Client(Socket s) throws IOException {
		sock = new WebSocket(s);

		synchronized(WebCTFServer.names) {
			if (WebCTFServer.names.size() > 0) {
				name = WebCTFServer.names.remove(0);
			}
		}
		synchronized(WebCTFServer.clients) {
			int team = 0;
			for(Client c : WebCTFServer.clients) {
				if (c.orange) { team++; } else { team--; }
			}
			orange = team < 0;
			WebCTFServer.clients.add(this);
		}
		WebCTFServer.log("client %s has connected from %s.", name, s.getInetAddress());
	}

	private int readInt(byte[] m, int offset) {
		return
			((m[offset]   & 0xFF) << 24) |
			((m[offset+1] & 0xFF) << 16) |
			((m[offset+2] & 0xFF) <<  8) |
			((m[offset+3] & 0xFF));
	}

	public void run() {
		try {
			while(true) {
				byte[] message = sock.readFrame();
				if (message.length < 1) { throw new IOException("connection closed."); }
				byte op = message[0];
				switch(op) {
					case 0:
						int dn = readInt(message, 1);
						synchronized(keys) { keys.add(dn); }
						break;
					case 1:
						int up = readInt(message, 1);
						synchronized(keys) { keys.remove(up); }
						break;
					case 2:
						int c = readInt(message, 1);
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
						mouseX = readInt(message, 1);
						mouseY = readInt(message, 5);
						break;
					default:
						//throw new Error("unknown input op " + op);
						WebCTFServer.log("unknown input op " + op);
				}
			}
		}
		catch(IOException e) {
			WebCTFServer.log("client %s has disconnected.", name);
			if (flag != null) {
				flag.carried = false;
				flag.x = x;
				flag.y = y;
			}
			synchronized(WebCTFServer.clients) { WebCTFServer.clients.remove(this); }
			synchronized(WebCTFServer.names)   { WebCTFServer.names.add(this.name); }
		}
	}

	void spawn() {
		List<Spawner> spawners = new ArrayList<Spawner>(WebCTFServer.spawners);
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
		dout.writeShort((short)x);
		dout.writeShort((short)y);
	}

	private int recolor(int c) {
		return (c << 8) | (c >>> 24);
	}

	void fill(int color) throws IOException {
		color = recolor(color);
		dout.writeByte(1);
		dout.writeInt(color);
	}

	void stroke(int color) throws IOException {
		color = recolor(color);
		dout.writeByte(2);
		dout.writeInt(color);
	}

	void poly(double... verts) throws IOException {
		dout.writeByte(3);
		dout.writeInt(verts.length/2);
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
		dout.writeByte(3);
		dout.writeInt(verts.size()/2);
		for(int z = 0; z < verts.size(); z += 2) {
			writePos(verts.get(z), verts.get(z+1));
		}
	}

	void beginFrame() throws IOException {
		dout.flush();
		bout.reset();
	}

	void endFrame() throws IOException {
		dout.flush();
		sock.writeFrame(bout.toByteArray());
		bout.reset();
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

	void ngon(double x, double y, double radius, int sides, double spin) throws IOException {
		Path2D s = new Path2D.Double();
		s.moveTo(
			x + radius * Math.cos(spin),
			y + radius * Math.sin(spin)
		);
		for(int z = 1; z < sides; z++) {
			s.lineTo(
				x + radius * Math.cos((Math.PI * 2 / sides * z) + spin),
				y + radius * Math.sin((Math.PI * 2 / sides * z) + spin)
			);
		}
		s.closePath();
		poly(s);
	}

	static double lerp(double a, double b, double t) {
		return a*(1-t) + b*(t);
	}

	static double easeQuart(double t, double duration, double start, double delta) {
		t = t / duration - 1;
		return -delta * (t*t*t*t - 1) + start; // ease out
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
		WebCTFServer.spawners.add(this);
		WebCTFServer.spawners.add(new Spawner(-x, -y, false));
	}
	
	private Spawner(int x, int y, boolean orange) {
		this.orange = orange;
		this.x = x * 10;
		this.y = y * 10;
		this.shape = WebCTFServer.box(x-1, y-1, 2, 2);
	}
}

class Flag {
	final boolean orange;
	final double bx;
	final double by;

	double x = 0;
	double y = 0;
	boolean carried = false;
	int capturetimer = 0;

	public Flag(int x, int y) {
		this(x, y, true);
		WebCTFServer.flags.add(this);
		WebCTFServer.flags.add(new Flag(-x, -y, false));
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