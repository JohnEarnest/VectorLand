VectorLand
==========

This is a prototype exploring a crazy idea I had recently. Mutiplayer games typically consist of a server component and a client. VectorLand pushes nearly all game logic into the server and keeps the client as simple as possible, resulting in a client which is completely generic and (in theory) reusable from game to game. The whole thing works a bit like VNC, except instead of transmitting compressed bitmaps of screen images, VectorLand draws everything as a series of filled polygons.

This project is a work in progress. All source code is provided as-is under the WTFPL.

Running Capture The Flag
------------------------

VectorLand CTF is the flagship game I'm developing to test the VectorLand protocol. Compile the server and run it on a beefy machine. It'll open port 10101 and wait for clients to connect.

		javac CTFServer.java
		java CTFServer

![VectorLand CTF in action](http://i.imgur.com/mbB7AQK.gif)

The client expects a server hostname as a commandline argument. The client is written such that if it loses communication with the server it will automatically reconnect when possible, making debugging painless.

		javac VectorLand.java
		java VectorLand localhost

As written the game should be able to handle at least 4 or so players over an internet connection, and possibly quite a few more over a LAN. Control player movement with ASWD, aim with the mouse and fire by clicking.

Have fun!