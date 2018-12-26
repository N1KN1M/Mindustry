package io.anuke.mindustry.graphics;

import io.anuke.arc.Core;
import io.anuke.arc.Events;
import io.anuke.arc.collection.Array;
import io.anuke.arc.collection.IntSet;
import io.anuke.arc.collection.Sort;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.glutils.FrameBuffer;
import io.anuke.arc.math.Mathf;
import io.anuke.mindustry.content.blocks.Blocks;
import io.anuke.mindustry.game.EventType.TileChangeEvent;
import io.anuke.mindustry.game.EventType.WorldLoadEvent;
import io.anuke.mindustry.game.Team;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;

import static io.anuke.arc.Core.camera;
import static io.anuke.mindustry.Vars.tilesize;
import static io.anuke.mindustry.Vars.world;

public class BlockRenderer{
    private final static int initialRequests = 32 * 32;
    private final static int expandr = 6;

    private FloorRenderer floorRenderer;

    private Array<BlockRequest> requests = new Array<>(true, initialRequests, BlockRequest.class);
    private IntSet teamChecks = new IntSet();
    private int lastCamX, lastCamY, lastRangeX, lastRangeY;
    private Layer lastLayer;
    private int requestidx = 0;
    private int iterateidx = 0;
    private FrameBuffer shadows = new FrameBuffer(1, 1);

    public BlockRenderer(){
        floorRenderer = new FloorRenderer();

        for(int i = 0; i < requests.size; i++){
            requests.set(i, new BlockRequest());
        }

        Events.on(WorldLoadEvent.class, event -> {
            lastCamY = lastCamX = -99; //invalidate camera position so blocks get updated
        });

        Events.on(TileChangeEvent.class, event -> {
            int avgx = (int)(camera.position.x / tilesize);
            int avgy = (int)(camera.position. y/ tilesize);
            int rangex = (int) (camera.width  / tilesize / 2) + 2;
            int rangey = (int) (camera.height  / tilesize / 2) + 2;

            if(Math.abs(avgx - event.tile.x) <= rangex && Math.abs(avgy - event.tile.y) <= rangey){
                lastCamY = lastCamX = -99; //invalidate camera position so blocks get updated
            }
        });
    }

    public void drawShadows(){
        Draw.color(0, 0, 0, 0.15f);
        Draw.rect().tex(shadows.getTexture()).center(
            camera.position.x - camera.position.x % tilesize,
            camera.position.y - camera.position.y % tilesize,
            shadows.getWidth(), -shadows.getHeight());
        Draw.color();
    }

    public boolean isTeamShown(Team team){
        return teamChecks.contains(team.ordinal());
    }

    /**Process all blocks to draw, simultaneously updating the block shadow framebuffer.*/
    public void processBlocks(){
        iterateidx = 0;
        lastLayer = null;

        int avgx = (int)(camera.position.x / tilesize);
        int avgy = (int)(camera.position.y / tilesize);

        int rangex = (int) (camera.width  / tilesize / 2) + 2;
        int rangey = (int) (camera.height  / tilesize / 2) + 2;

        if(avgx == lastCamX && avgy == lastCamY && lastRangeX == rangex && lastRangeY == rangey){
            return;
        }

        int shadowW = rangex * tilesize*2, shadowH = rangey * tilesize*2;

        teamChecks.clear();
        requestidx = 0;

        Draw.flush();
        Core.graphics.batch().getProjection()
        .setOrtho(Mathf.round(camera.position.x, tilesize)-shadowW/2f, Mathf.round(camera.position.y, tilesize)-shadowH/2f,
        shadowW, shadowH);

        if(shadows.getWidth() != shadowW || shadows.getHeight() != shadowH){
            shadows.resize(shadowW, shadowH);
        }

        shadows.begin();

        int minx = Math.max(avgx - rangex - expandr, 0);
        int miny = Math.max(avgy - rangey - expandr, 0);
        int maxx = Math.min(world.width() - 1, avgx + rangex + expandr);
        int maxy = Math.min(world.height() - 1, avgy + rangey + expandr);

        for(int x = minx; x <= maxx; x++){
            for(int y = miny; y <= maxy; y++){
                boolean expanded = (Math.abs(x - avgx) > rangex || Math.abs(y - avgy) > rangey);
                Tile tile = world.rawTile(x, y);

                if(tile != null){
                    Block block = tile.block();
                    Team team = tile.getTeam();

                    if(!expanded && block != Blocks.air && world.isAccessible(x, y)){
                        tile.block().drawShadow(tile);
                    }

                    if(block != Blocks.air){
                        if(!expanded){
                            addRequest(tile, Layer.block);
                            teamChecks.add(team.ordinal());
                        }

                        if(block.expanded || !expanded){
                            if(block.layer != null && block.isLayer(tile)){
                                addRequest(tile, block.layer);
                            }

                            if(block.layer2 != null && block.isLayer2(tile)){
                                addRequest(tile, block.layer2);
                            }
                        }
                    }
                }
            }
        }

        shadows.end();

        Draw.flush();
        Draw.projection(camera.projection());

        Sort.instance().sort(requests.items, 0, requestidx);

        lastCamX = avgx;
        lastCamY = avgy;
        lastRangeX = rangex;
        lastRangeY = rangey;
    }

    public int getRequests(){
        return requestidx;
    }

    public void drawBlocks(Layer stopAt){

        for(; iterateidx < requestidx; iterateidx++){

            if(iterateidx < requests.size && requests.get(iterateidx).layer.ordinal() > stopAt.ordinal()){
                break;
            }

            BlockRequest req = requests.get(iterateidx);

            if(req.layer != lastLayer){
                if(lastLayer != null) layerEnds(lastLayer);
                layerBegins(req.layer);
            }

            Block block = req.tile.block();

            if(req.layer == Layer.block){
                block.draw(req.tile);
            }else if(req.layer == block.layer){
                block.drawLayer(req.tile);
            }else if(req.layer == block.layer2){
                block.drawLayer2(req.tile);
            }

            lastLayer = req.layer;
        }
    }

    public void drawTeamBlocks(Layer layer, Team team){
        int index = this.iterateidx;

        for(; index < requestidx; index++){

            if(index < requests.size && requests.get(index).layer.ordinal() > layer.ordinal()){
                break;
            }

            BlockRequest req = requests.get(index);
            if(req.tile.getTeam() != team) continue;

            Block block = req.tile.block();

            if(req.layer == Layer.block){
                block.draw(req.tile);
            }else if(req.layer == block.layer){
                block.drawLayer(req.tile);
            }else if(req.layer == block.layer2){
                block.drawLayer2(req.tile);
            }

        }
    }

    public void skipLayer(Layer stopAt){

        for(; iterateidx < requestidx; iterateidx++){
            if(iterateidx < requests.size && requests.get(iterateidx).layer.ordinal() > stopAt.ordinal()){
                break;
            }
        }
    }

    public void beginFloor(){
        floorRenderer.beginDraw();
    }

    public void endFloor(){
        floorRenderer.endDraw();
    }

    public void drawFloor(){
        floorRenderer.drawFloor();
    }

    private void layerBegins(Layer layer){
    }

    private void layerEnds(Layer layer){
    }

    private void addRequest(Tile tile, Layer layer){
        if(requestidx >= requests.size){
            requests.add(new BlockRequest());
        }
        BlockRequest r = requests.get(requestidx);
        if(r == null){
            requests.set(requestidx, r = new BlockRequest());
        }
        r.tile = tile;
        r.layer = layer;
        requestidx++;
    }

    private class BlockRequest implements Comparable<BlockRequest>{
        Tile tile;
        Layer layer;

        @Override
        public int compareTo(BlockRequest other){
            return layer.compareTo(other.layer);
        }

        @Override
        public String toString(){
            return tile.block().name + ":" + layer.toString();
        }
    }
}
