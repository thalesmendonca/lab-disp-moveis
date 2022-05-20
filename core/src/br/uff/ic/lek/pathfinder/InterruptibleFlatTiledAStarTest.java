package br.uff.ic.lek.pathfinder;


import br.uff.ic.lek.pathfinder.FlatTiledGraph;
import br.uff.ic.lek.pathfinder.FlatTiledNode;
import br.uff.ic.lek.pathfinder.PathFinderTestBase;
import br.uff.ic.lek.pathfinder.PathFinderTests;
import br.uff.ic.lek.pathfinder.TiledManhattanDistance;
import br.uff.ic.lek.pathfinder.TiledNode;
import br.uff.ic.lek.pathfinder.TiledRaycastCollisionDetector;
import br.uff.ic.lek.pathfinder.TiledSmoothableGraphPath;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.ai.msg.MessageManager;
import com.badlogic.gdx.ai.msg.Telegram;
import com.badlogic.gdx.ai.msg.Telegraph;
import com.badlogic.gdx.ai.pfa.PathFinderQueue;
import com.badlogic.gdx.ai.pfa.PathFinderRequest;
import com.badlogic.gdx.ai.pfa.PathFinderRequestControl;
import com.badlogic.gdx.ai.pfa.PathSmoother;
import com.badlogic.gdx.ai.pfa.PathSmootherRequest;
import com.badlogic.gdx.ai.pfa.indexed.IndexedAStarPathFinder;
import com.badlogic.gdx.ai.pfa.indexed.IndexedAStarPathFinder.Metrics;
import com.badlogic.gdx.ai.sched.LoadBalancingScheduler;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.Pool.Poolable;

/** This test shows interruptible flat pathfinding through a {@link PathFinderQueue}.
 * 
 * @author davebaol */
public class InterruptibleFlatTiledAStarTest extends PathFinderTestBase implements Telegraph {

	final static float width = 8; // 5; // 10;

	final static int PF_REQUEST = 1;
	final static int PF_RESPONSE = 2;

	ShapeRenderer renderer;
	Vector3 tmpUnprojection = new Vector3();

	int lastScreenX;
	int lastScreenY;
	int lastEndTileX;
	int lastEndTileY;
	int startTileX;
	int startTileY;

	FlatTiledGraph worldMap;
	TiledSmoothableGraphPath<FlatTiledNode> path;
	TiledManhattanDistance<FlatTiledNode> heuristic;
	IndexedAStarPathFinder<FlatTiledNode> pathFinder;
	PathSmoother<FlatTiledNode, Vector2> pathSmoother;
	PathSmootherRequest<FlatTiledNode, Vector2> pathSmootherRequest;

	Pool<MyPathFinderRequest> requestPool;

	LoadBalancingScheduler scheduler;

	boolean smooth = false;

	CheckBox checkDiagonal;
	CheckBox checkSmooth;
	CheckBox checkMetrics;
	Slider sliderMillisAvailablePerFrame;

	public InterruptibleFlatTiledAStarTest (PathFinderTests container) {
		super(container, "Interruptible Flat Tiled A*");
	}

	@Override
	public void create (Table table) {
		lastEndTileX = -1;
		lastEndTileY = -1;
		startTileX = 1;
		startTileY = 1;

		// Create the map
		worldMap = new FlatTiledGraph();
		int roomCount = MathUtils.random(80, 150);// 100, 260);//70, 120);
		int roomMinSize = 3;
		int roomMaxSize = 15;
		int squashIterations = 100;
		worldMap.init(roomCount, roomMinSize, roomMaxSize, squashIterations);

		path = new TiledSmoothableGraphPath<FlatTiledNode>();
		heuristic = new TiledManhattanDistance<FlatTiledNode>();
		pathFinder = new IndexedAStarPathFinder<FlatTiledNode>(worldMap, true);
		pathSmoother = new PathSmoother<FlatTiledNode, Vector2>(new TiledRaycastCollisionDetector<FlatTiledNode>(worldMap));
		pathSmootherRequest = new PathSmootherRequest<FlatTiledNode, Vector2>();

		requestPool = new Pool<MyPathFinderRequest>() {
			@Override
			protected MyPathFinderRequest newObject () {
				return new MyPathFinderRequest();
			}
		};
		PathFinderQueue<FlatTiledNode> pathFinderQueue = new PathFinderQueue<FlatTiledNode>(pathFinder);
		MessageManager.getInstance().addListener(pathFinderQueue, PF_REQUEST);

		scheduler = new LoadBalancingScheduler(100);
		scheduler.add(pathFinderQueue, 1, 0);

		renderer = new ShapeRenderer();
		inputProcessor = new TiledAStarInputProcessor(this);

		Table detailTable = new Table(container.skin);

		detailTable.row();
		checkSmooth = new CheckBox("[RED]S[]mooth Path", container.skin);
		checkSmooth.setChecked(smooth);
		checkSmooth.addListener(new ChangeListener() {
			@Override
			public void changed (ChangeEvent event, Actor actor) {
				CheckBox checkBox = (CheckBox)event.getListenerActor();
				smooth = checkBox.isChecked();
				updatePath(true);
			}
		});
		detailTable.add(checkSmooth);

		detailTable.row();
		checkDiagonal = new CheckBox("Prefer [RED]D[]iagonal", container.skin);
		checkDiagonal.setChecked(worldMap.diagonal);
		checkDiagonal.addListener(new ChangeListener() {
			@Override
			public void changed (ChangeEvent event, Actor actor) {
				CheckBox checkBox = (CheckBox)event.getListenerActor();
				worldMap.diagonal = checkBox.isChecked();
				updatePath(true);
			}
		});
		detailTable.add(checkDiagonal);

		detailTable.row();
		addSeparator(detailTable);

		detailTable.row();
		checkMetrics = new CheckBox("Calculate [RED]M[]etrics", container.skin);
		checkMetrics.setChecked(pathFinder.metrics != null);
		checkMetrics.addListener(new ChangeListener() {
			@Override
			public void changed (ChangeEvent event, Actor actor) {
				CheckBox checkBox = (CheckBox)event.getListenerActor();
				pathFinder.metrics = checkBox.isChecked() ? new Metrics() : null;
				updatePath(true);
			}
		});
		detailTable.add(checkMetrics);

		detailTable.row();
		addSeparator(detailTable);

		detailTable.row();
		sliderMillisAvailablePerFrame = new Slider(0.1f, 40f, 0.1f, false, container.skin);
		sliderMillisAvailablePerFrame.setValue(16);
		final Label labelMillisAvailablePerFrame = new Label("Millis Available per Frame [["
			+ sliderMillisAvailablePerFrame.getValue() + "]", container.skin);
		detailTable.add(labelMillisAvailablePerFrame);
		detailTable.row();
		sliderMillisAvailablePerFrame.addListener(new ChangeListener() {
			@Override
			public void changed (ChangeEvent event, Actor actor) {
				labelMillisAvailablePerFrame.setText("Millis Available per Frame [[" + sliderMillisAvailablePerFrame.getValue() + "]");
			}
		});
		Table sliderMapfTable = new Table();
		sliderMapfTable.add(new Label("[RED]-[]  ", container.skin));
		sliderMapfTable.add(sliderMillisAvailablePerFrame);
		sliderMapfTable.add(new Label("  [RED]+[]", container.skin));
		detailTable.add(sliderMapfTable);

		detailWindow = createDetailWindow(detailTable);
	}

	@Override
	public void render () {
		long timeToRun = (long)(sliderMillisAvailablePerFrame.getValue() * 1000000f);
		scheduler.run(timeToRun);

		renderer.begin(ShapeType.Filled);
		for (int x = 0; x < FlatTiledGraph.sizeX; x++) {
			for (int y = 0; y < FlatTiledGraph.sizeY; y++) {
				switch (worldMap.getNode(x, y).type) {
				case TiledNode.TILE_FLOOR:
					renderer.setColor(Color.WHITE);
					break;
				case TiledNode.TILE_WALL:
					renderer.setColor(Color.GRAY);
					break;
				default:
					renderer.setColor(Color.BLACK);
					break;
				}
				renderer.rect(x * width, y * width, width, width);
			}
		}

		renderer.setColor(Color.RED);
		int nodeCount = path.getCount();
		for (int i = 0; i < nodeCount; i++) {
			FlatTiledNode node = path.nodes.get(i);
			renderer.rect(node.x * width, node.y * width, width, width);
		}
		if (smooth) {
			renderer.end();
			renderer.begin(ShapeType.Line);
			float hw = width / 2f;
			if (nodeCount > 0) {
				FlatTiledNode prevNode = path.nodes.get(0);
				for (int i = 1; i < nodeCount; i++) {
					FlatTiledNode node = path.nodes.get(i);
					renderer.line(node.x * width + hw, node.y * width + hw, prevNode.x * width + hw, prevNode.y * width + hw);
					prevNode = node;
				}
			}
		}
		renderer.end();
	}

	@Override
	public void dispose () {
		renderer.dispose();

		worldMap = null;
		path = null;
		heuristic = null;
		pathFinder = null;
		pathSmoother = null;
		scheduler = null;

		MessageManager.getInstance().clear();
	}

	public Camera getCamera () {
		return container.stage.getViewport().getCamera();
	}

	@Override
	public boolean handleMessage (Telegram telegram) {
		switch (telegram.message) {
		case PF_RESPONSE: // PathFinderQueue will call us directly, no need to register for this message
			MyPathFinderRequest pfr = (MyPathFinderRequest)telegram.extraInfo;
			if (PathFinderRequestControl.DEBUG) {
				@SuppressWarnings("unchecked")
				PathFinderQueue<FlatTiledNode> pfQueue = (PathFinderQueue<FlatTiledNode>)telegram.sender;
				System.out.println("pfQueue.size = " + pfQueue.size() + " executionFrames = " + pfr.executionFrames);
			}
			requestPool.free(pfr);
			break;
		}
		return true;
	}

	private void updatePath (boolean forceUpdate) {
		getCamera().unproject(tmpUnprojection.set(lastScreenX, lastScreenY, 0));
		int tileX = (int)(tmpUnprojection.x / width);
		int tileY = (int)(tmpUnprojection.y / width);
		if (forceUpdate || tileX != lastEndTileX || tileY != lastEndTileY) {
			final FlatTiledNode startNode = worldMap.getNode(startTileX, startTileY);
			FlatTiledNode endNode = worldMap.getNode(tileX, tileY);
			if (forceUpdate || endNode.type == TiledNode.TILE_FLOOR) {
				if (endNode.type == TiledNode.TILE_FLOOR) {
					lastEndTileX = tileX;
					lastEndTileY = tileY;
				} else {
					endNode = worldMap.getNode(lastEndTileX, lastEndTileY);
				}

				MyPathFinderRequest pfRequest = requestPool.obtain();
				pfRequest.startNode = startNode;
				pfRequest.endNode = endNode;
				pfRequest.heuristic = heuristic;
				pfRequest.resultPath = path;
				pfRequest.responseMessageCode = PF_RESPONSE;
				MessageManager.getInstance().dispatchMessage(this, PF_REQUEST, pfRequest);
// worldMap.startNode = startNode;
// long startTime = nanoTime();
// pathFinder.searchNodePath(startNode, endNode, heuristic, path);
// if (pathFinder.metrics != null) {
// float elapsed = (TimeUtils.nanoTime() - startTime) / 1000000f;
// System.out.println("----------------- Indexed A* Path Finder Metrics -----------------");
// System.out.println("Visited nodes................... = " + pathFinder.metrics.visitedNodes);
// System.out.println("Open list additions............. = " + pathFinder.metrics.openListAdditions);
// System.out.println("Open list peak.................. = " + pathFinder.metrics.openListPeak);
// System.out.println("Path finding elapsed time (ms).. = " + elapsed);
// }
// if (smooth) {
// startTime = nanoTime();
// pathSmoother.smoothPath(path);
// if (pathFinder.metrics != null) {
// float elapsed = (TimeUtils.nanoTime() - startTime) / 1000000f;
// System.out.println("Path smoothing elapsed time (ms) = " + elapsed);
// }
// }
			}
		}
	}

	/** An {@link InputProcessor} that allows you to define a path to find.
	 * 
	 * @autor davebaol */
	static class TiledAStarInputProcessor extends InputAdapter {
		InterruptibleFlatTiledAStarTest test;

		public TiledAStarInputProcessor (InterruptibleFlatTiledAStarTest test) {
			this.test = test;
		}

		@Override
		public boolean keyTyped (char character) {
			switch (character) {
			case 'm':
			case 'M':
				test.checkMetrics.toggle();
				break;
			case 'd':
			case 'D':
				test.checkDiagonal.toggle();
				break;
			case 's':
			case 'S':
				test.checkSmooth.toggle();
				break;
			case '-':
				test.sliderMillisAvailablePerFrame.setValue(test.sliderMillisAvailablePerFrame.getValue()
					- test.sliderMillisAvailablePerFrame.getStepSize());
				break;
			case '+':
				test.sliderMillisAvailablePerFrame.setValue(test.sliderMillisAvailablePerFrame.getValue()
					+ test.sliderMillisAvailablePerFrame.getStepSize());
				break;
			}
			return true;
		}

		@Override
		public boolean touchUp (int screenX, int screenY, int pointer, int button) {
			test.getCamera().unproject(test.tmpUnprojection.set(screenX, screenY, 0));
			int tileX = (int)(test.tmpUnprojection.x / width);
			int tileY = (int)(test.tmpUnprojection.y / width);
			FlatTiledNode startNode = test.worldMap.getNode(tileX, tileY);
			if (startNode.type == TiledNode.TILE_FLOOR) {
				test.startTileX = tileX;
				test.startTileY = tileY;
				test.updatePath(true);
			}
			return true;
		}

		@Override
		public boolean mouseMoved (int screenX, int screenY) {
			test.lastScreenX = screenX;
			test.lastScreenY = screenY;
			test.updatePath(false);
			return true;
		}
	}

	class MyPathFinderRequest extends PathFinderRequest<FlatTiledNode> implements Poolable {
		boolean smoothFinished;

		public MyPathFinderRequest () {
		}

		@Override
		public boolean initializeSearch (long timeToRun) {
			resultPath.clear();
			worldMap.startNode = startNode;
			return true;
		}

		@Override
		public boolean finalizeSearch (long timeToRun) {
			if (statusChanged) {
				pathSmootherRequest.refresh(path);
				smoothFinished = false;
			}
			if (pathFound && smooth && !smoothFinished) {
				smoothFinished = pathSmoother.smoothPath(pathSmootherRequest, timeToRun);
				if (!smoothFinished) return false;
			}
			return true;
		}

		@Override
		public void reset () {
			this.startNode = null;
			this.endNode = null;
			this.heuristic = null;
			this.resultPath = null;
			this.client = null;
		}
	}
}
