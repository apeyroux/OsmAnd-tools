package net.osmand.data.diff;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TLongObjectHashMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.osmand.IndexConstants;
import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryIndexPart;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapIndexReader.MapRoot;
import net.osmand.binary.BinaryMapIndexReader.SearchFilter;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapPoiReaderAdapter.PoiRegion;
import net.osmand.binary.MapZooms;
import net.osmand.binary.MapZooms.MapZoomPair;
import net.osmand.binary.OsmandOdb;
import net.osmand.data.Amenity;
import net.osmand.data.index.IndexUploader;
import net.osmand.data.preparation.AbstractIndexPartCreator;
import net.osmand.data.preparation.BinaryFileReference;
import net.osmand.data.preparation.BinaryMapIndexWriter;
import net.osmand.data.preparation.IndexVectorMapCreator;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;
import rtree.LeafElement;
import rtree.RTree;
import rtree.RTreeException;
import rtree.Rect;

import com.google.protobuf.CodedOutputStream;

public class ObfFileInMemory {
	private static final int ZOOM_LEVEL_POI = 15;
	private double lattop = 85;
	private double latbottom = -85;
	private double lonleft = -179.9;
	private double lonright = 179.9;
	

	private Map<MapZooms.MapZoomPair, TLongObjectHashMap<BinaryMapDataObject>> mapObjects = new LinkedHashMap<>();
	private long timestamp = 0;
	private MapIndex mapIndex = new MapIndex(); 

	public TLongObjectHashMap<BinaryMapDataObject> get(MapZooms.MapZoomPair zoom) {
		if (!mapObjects.containsKey(zoom)) {
			mapObjects.put(zoom, new TLongObjectHashMap<BinaryMapDataObject>());
		}
		return mapObjects.get(zoom);
	}
	
	public Collection<MapZooms.MapZoomPair> getZooms() {
		return mapObjects.keySet();
	}
	
	public MapIndex getMapIndex() {
		return mapIndex;
	}
	
	
	public void putMapObjects(MapZoomPair pair, Collection<BinaryMapDataObject> objects, boolean override) {
		TLongObjectHashMap<BinaryMapDataObject> res = get(pair);
		for(BinaryMapDataObject o: objects) {
			o = mapIndex.adoptMapObject(o);
			if(override) {
				res.put(o.getId(), o);
			} else if(!res.containsKey(o.getId())){
				res.put(o.getId(), o);
			}
			
		}
	}

	public void writeFile(File targetFile) throws IOException, RTreeException {
		boolean gzip = targetFile.getName().endsWith(".gz");
		File nonGzip = targetFile;
		if(gzip) {
			nonGzip = new File(targetFile.getParentFile(), 
				targetFile.getName().substring(0, targetFile.getName().length() - 3));
		}
		final RandomAccessFile raf = new RandomAccessFile(nonGzip, "rw");
		// write files
		CodedOutputStream ous = CodedOutputStream.newInstance(new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				raf.write(b);
			}

			@Override
			public void write(byte[] b) throws IOException {
				raf.write(b);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				raf.write(b, off, len);
			}

		});

		timestamp = timestamp == 0 ? System.currentTimeMillis() : timestamp;
		int version = IndexConstants.BINARY_MAP_VERSION;
		ous.writeInt32(OsmandOdb.OsmAndStructure.VERSION_FIELD_NUMBER, version);
		ous.writeInt64(OsmandOdb.OsmAndStructure.DATECREATED_FIELD_NUMBER, timestamp);
		BinaryMapIndexWriter writer = new BinaryMapIndexWriter(raf, ous);
		if (mapObjects.size() > 0) {
			String name = mapIndex.getName();
			if(Algorithms.isEmpty(name)) {
				name = targetFile.getName().substring(0, targetFile.getName().indexOf('.'));
			}
			writer.startWriteMapIndex(Algorithms.capitalizeFirstLetter(name));
			writer.writeMapEncodingRules(mapIndex.decodingRules);
			Iterator<Entry<MapZoomPair, TLongObjectHashMap<BinaryMapDataObject>>> it = mapObjects.entrySet().iterator();
			while (it.hasNext()) {
				Entry<MapZoomPair, TLongObjectHashMap<BinaryMapDataObject>> n = it.next();
				writeMapData(writer, n.getKey(), n.getValue(), targetFile);
			}
			writer.endWriteMapIndex();
		}
		// TODO Write POI, Routing, Transport
		ous.writeInt32(OsmandOdb.OsmAndStructure.VERSIONCONFIRM_FIELD_NUMBER, version);
		ous.flush();
		raf.close();
		
		if (gzip) {
			nonGzip.setLastModified(timestamp);

			FileInputStream fis = new FileInputStream(nonGzip);
			GZIPOutputStream gzout = new GZIPOutputStream(new FileOutputStream(targetFile));
			Algorithms.streamCopy(fis, gzout);
			fis.close();
			gzout.close();
			nonGzip.delete();
		}
		targetFile.setLastModified(timestamp);
	}

	private void writeMapData(BinaryMapIndexWriter writer, MapZoomPair mapZoomPair,
			TLongObjectHashMap<BinaryMapDataObject> objects, File fileToWrite) throws IOException, RTreeException {
		File nonpackRtree = new File(fileToWrite.getParentFile(), "nonpack" + mapZoomPair.getMinZoom() + "."
				+ fileToWrite.getName() + ".rtree");
		File packRtree = new File(fileToWrite.getParentFile(), "pack" + mapZoomPair.getMinZoom() + "."
				+ fileToWrite.getName() + ".rtree");
		RTree rtree = null;
		try {
			rtree = new RTree(nonpackRtree.getAbsolutePath());
			for (long key : objects.keys()) {
				BinaryMapDataObject obj = objects.get(key);
				int minX = obj.getPoint31XTile(0);
				int maxX = obj.getPoint31XTile(0);
				int maxY = obj.getPoint31YTile(0);
				int minY = obj.getPoint31YTile(0);
				for (int i = 1; i < obj.getPointsLength(); i++) {
					minX = Math.min(minX, obj.getPoint31XTile(i));
					minY = Math.min(minY, obj.getPoint31YTile(i));
					maxX = Math.max(maxX, obj.getPoint31XTile(i));
					maxY = Math.max(maxY, obj.getPoint31YTile(i));
				}
				try {
					rtree.insert(new LeafElement(new Rect(minX, minY, maxX, maxY), obj.getId()));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
			rtree = AbstractIndexPartCreator.packRtreeFile(rtree, nonpackRtree.getAbsolutePath(),
					packRtree.getAbsolutePath());
			TLongObjectHashMap<BinaryFileReference> treeHeader = new TLongObjectHashMap<BinaryFileReference>();

			long rootIndex = rtree.getFileHdr().getRootIndex();
			rtree.Node root = rtree.getReadNode(rootIndex);
			Rect rootBounds = IndexUploader.calcBounds(root);
			if (rootBounds != null) {
				writer.startWriteMapLevelIndex(mapZoomPair.getMinZoom(), mapZoomPair.getMaxZoom(),
						rootBounds.getMinX(), rootBounds.getMaxX(), rootBounds.getMinY(), rootBounds.getMaxY());
				IndexVectorMapCreator.writeBinaryMapTree(root, rootBounds, rtree, writer, treeHeader);

				IndexUploader.writeBinaryMapBlock(root, rootBounds, rtree, writer, treeHeader, objects, mapZoomPair);
				writer.endWriteMapLevelIndex();

			}
		} finally {
			if (rtree != null) {
				RandomAccessFile file = rtree.getFileHdr().getFile();
				file.close();
			}
			nonpackRtree.delete();
			packRtree.delete();
			RTree.clearCache();
		}

	}

	public void updateTimestamp(long dateCreated) {
		timestamp = Math.max(timestamp, dateCreated);
	}
	
	public long getTimestamp() {
		return timestamp;
	}

	public void readObfFiles(List<File> files) throws IOException {
		// TODO READ POI, Routing, Transport
		for (int i = 0; i < files.size(); i++) {
			File inputFile = files.get(i);
			File nonGzip = inputFile;
			boolean gzip = false;
			if(inputFile.getName().endsWith(".gz")) {
				nonGzip = new File(inputFile.getParentFile(), inputFile.getName().substring(0, inputFile.getName().length() - 3));
				GZIPInputStream gzin = new GZIPInputStream(new FileInputStream(inputFile));
				FileOutputStream fous = new FileOutputStream(nonGzip);
				Algorithms.streamCopy(gzin, fous);
				fous.close();
				gzin.close();
				gzip = true;
			}
			RandomAccessFile raf = new RandomAccessFile(nonGzip, "r");
			BinaryMapIndexReader indexReader = new BinaryMapIndexReader(raf, nonGzip);
			for (BinaryIndexPart p : indexReader.getIndexes()) {
				if(p instanceof MapIndex) {
					MapIndex mi = (MapIndex) p;
					for(MapRoot mr : mi.getRoots()) {
						MapZooms.MapZoomPair pair = new MapZooms.MapZoomPair(mr.getMinZoom(), mr.getMaxZoom());
						TLongObjectHashMap<BinaryMapDataObject> objects = getBinaryMapData(indexReader, mr.getMinZoom());
						putMapObjects(pair, objects.valueCollection(), true);
					}
				}
			}
			updateTimestamp(indexReader.getDateCreated());
			indexReader.close();
			raf.close();
			if(gzip) {
				nonGzip.delete();
			}
		}
	}

	private TLongObjectHashMap<BinaryMapDataObject> getBinaryMapData(BinaryMapIndexReader index, int zoom) throws IOException {
		final TLongObjectHashMap<BinaryMapDataObject> result = new TLongObjectHashMap<>();
		for (BinaryIndexPart p : index.getIndexes()) {
			if(p instanceof MapIndex) {
				MapIndex m = ((MapIndex) p);
				final SearchRequest<BinaryMapDataObject> req = BinaryMapIndexReader.buildSearchRequest(
						MapUtils.get31TileNumberX(lonleft),
						MapUtils.get31TileNumberX(lonright),
						MapUtils.get31TileNumberY(lattop),
						MapUtils.get31TileNumberY(latbottom),
						zoom,
						new SearchFilter() {
							@Override
							public boolean accept(TIntArrayList types, MapIndex index) {
								return true;
							}
						},
						new ResultMatcher<BinaryMapDataObject>() {
							@Override
							public boolean publish(BinaryMapDataObject obj) {
								result.put(obj.getId(), obj);
								return false;
							}

							@Override
							public boolean isCancelled() {
								return false;
							}
						});
				index.searchMapIndex(req, m);
			} 
		}
		return result;
	}
	
	private List<Amenity> getPoiData(BinaryMapIndexReader index) throws IOException {
		final List<Amenity> amenities = new ArrayList<>();
		for (BinaryIndexPart p : index.getIndexes()) {
			if (p instanceof PoiRegion) {				
				SearchRequest<Amenity> req = BinaryMapIndexReader.buildSearchPoiRequest(
					MapUtils.get31TileNumberX(lonleft),
					MapUtils.get31TileNumberX(lonright),
					MapUtils.get31TileNumberY(lattop),
					MapUtils.get31TileNumberY(latbottom),
					ZOOM_LEVEL_POI,
					BinaryMapIndexReader.ACCEPT_ALL_POI_TYPE_FILTER,
					new ResultMatcher<Amenity>() {
						@Override
						public boolean publish(Amenity object) {
							amenities.add(object);
							return false;
						}

						@Override
						public boolean isCancelled() {
							return false;
						}
					});
				index.initCategories((PoiRegion) p);
				index.searchPoi((PoiRegion) p, req);
			}
		}
		return amenities;
	}

	public void filterAllZoomsBelow(int zm) {
		for(MapZoomPair mz : new ArrayList<>(getZooms())) {
			if(mz.getMaxZoom() < zm) {
				mapObjects.remove(mz);
			}
		}		
	}
}