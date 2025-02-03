#!/usr/bin/env node
/**
 * download_and_rotate_optimized_nodeio.js
 *
 * 1) Gets elevation for lat/lon from Google Elevation API.
 * 2) Fetches the root tileset (root.json).
 * 3) Recursively traverses the 3D Tiles hierarchy:
 *      - Skip parent tile content if children exist.
 *      - Keep "session" param from child URIs for deeper LOD.
 *      - If boundingVolume intersects, gather any .glb leaves.
 * 4) The first .glb sets ORIGIN_TRANSLATION; subsequent .glb in parallel.
 * 5) No tmp file for each tile: NodeIO reads the .glb over HTTPS, rotates in memory,
 *    and writes the final .glb to disk.
 * 6) For each tile, we also print a line: TILE_TRANSLATION <sha> [x, y, z]
 *    where [x, y, z] is the first node's final translation in that tile.
 *
 * Usage example:
 *   node download_and_rotate_optimized_nodeio.js \
 *       --key YOUR_API_KEY \
 *       --lng 139.64847 --lat 35.67496 --radius 250 \
 *       --out ./output
 *   # Optionally set a custom origin:
 *   node download_and_rotate_optimized_nodeio.js \
 *       --key YOUR_API_KEY \
 *       --lng 139.64847 --lat 35.67496 --radius 250 \
 *       --out ./output \
 *       --origin 12345.67 89012.34 -1234.56
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import yargs from 'yargs/yargs';
import { hideBin } from 'yargs/helpers';
// If Node < 18, you may need to import fetch from 'node-fetch'

import PQueue from 'p-queue';
import crypto from 'crypto';

import { NodeIO } from '@gltf-transform/core';
import { KHRDracoMeshCompression, KHRMaterialsUnlit } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';
import { quat, mat4, vec3 } from 'gl-matrix';

import http from 'http';
import https from 'https';
import axios from 'axios';

// Keep-alive
const httpAgent = new http.Agent({ keepAlive: true });
const httpsAgent = new https.Agent({ keepAlive: true });
const axiosInstance = axios.create({ httpAgent, httpsAgent, timeout: 30000 });

/* ---------------------------------------------------------------------------
   Global NodeIO
   --------------------------------------------------------------------------- */
let GLOBAL_IO = null;
async function getGlobalIO() {
    if (GLOBAL_IO) return GLOBAL_IO;
    const io = new NodeIO(fetch).setAllowNetwork(true);
    const dracoDecoder = await draco3d.createDecoderModule();
    const dracoEncoder = await draco3d.createEncoderModule();
    io.registerExtensions([KHRDracoMeshCompression, KHRMaterialsUnlit])
       .registerDependencies({
          'draco3d.decoder': dracoDecoder,
          'draco3d.encoder': dracoEncoder,
       });
    GLOBAL_IO = io;
    return io;
}

/* ---------------------------------------------------------------------------
   1) Rotation logic
   --------------------------------------------------------------------------- */

function normalizeVec(v) {
    const len = Math.hypot(...v);
    return (len === 0) ? [0, 0, 0] : [v[0] / len, v[1] / len, v[2] / len];
}

/**
 * Rotate the given Document so that ECEF "up" → +Y up,
 * preserving a single shared origin (the first node's translation).
 *
 * Returns { finalOrigin, firstNodeTranslation }.
 */
function rotateDocument(doc, originECEF) {
    const root = doc.getRoot();
    const nodes = root.listNodes();

    let localOrigin = originECEF ? originECEF.slice() : null;
    let firstNodeTranslation = null;

    for (const node of nodes) {
        const t = node.getTranslation();
        if (!t) continue;

        // If we haven't already set the global origin, use this node's ECEF.
        if (!localOrigin) {
            localOrigin = t.slice();
            console.log(`ORIGIN_TRANSLATION ${JSON.stringify(localOrigin)}`);
        }

        // Compute translation relative to the origin.
        const relative = [
            t[0] - localOrigin[0],
            t[1] - localOrigin[1],
            t[2] - localOrigin[2],
        ];

        // The 'up' in ECEF is the direction of (x, y, z).
        const ecefUp = normalizeVec(t);
        // We want to rotate so that ECEF up becomes +Y in glTF.
        const desiredUp = [0, 1, 0];

        // Create a rotation quaternion from ecefUp → desiredUp.
        const rQ = quat.create();
        quat.rotationTo(rQ, ecefUp, desiredUp);
        const rotMatrix = mat4.fromQuat([], rQ);

        // Rotate the node translation.
        const rotatedT = vec3.transformMat4([], relative, rotMatrix);

        // Rotate geometry vertices.
        const mesh = node.getMesh();
        if (mesh) {
            for (const prim of mesh.listPrimitives()) {
                const pos = prim.getAttribute('POSITION');
                if (!pos) continue;
                const arr = pos.getArray();
                for (let i = 0; i < arr.length; i += 3) {
                    const v0 = [arr[i], arr[i + 1], arr[i + 2]];
                    const v1 = vec3.transformMat4([], v0, rotMatrix);
                    arr[i] = v1[0];
                    arr[i + 1] = v1[1];
                    arr[i + 2] = v1[2];
                }
                pos.setArray(arr);
            }
        }

        // Clear any rotation on the node itself; we baked it into vertices & translation.
        node.setRotation([0, 0, 0, 1]);
        node.setTranslation(rotatedT);

        // If this is the first node, keep a record of its final translation to log.
        if (!firstNodeTranslation) {
            firstNodeTranslation = rotatedT.slice();
        }
    }

    return {
        finalOrigin: localOrigin || [0, 0, 0],
        firstNodeTranslation: firstNodeTranslation || [0, 0, 0],
    };
}

/* ---------------------------------------------------------------------------
   2) Elevation fetch
   --------------------------------------------------------------------------- */
async function getElevation(apiKey, lat, lng) {
    const url = 'https://maps.googleapis.com/maps/api/elevation/json';
    const resp = await axiosInstance.get(url, {
        params: { locations: `${lat},${lng}`, key: apiKey },
    });
    if (resp.status !== 200 || !resp.data || resp.data.status !== 'OK') {
        throw new Error(`Elevation API error: ${JSON.stringify(resp.data)}`);
    }
    return resp.data.results[0].elevation;
}

/* ---------------------------------------------------------------------------
   3) Culling math: sphere for boundingVolume
   --------------------------------------------------------------------------- */
class Sphere {
    constructor(center, radius) {
        this.center = center;
        this.radius = radius;
    }
    intersects(other) {
        const dx = other.center[0] - this.center[0];
        const dy = other.center[1] - this.center[1];
        const dz = other.center[2] - this.center[2];
        const dist = Math.hypot(dx, dy, dz);
        return dist < (this.radius + other.radius);
    }
}

function obbToSphere(boxSpec) {
    // boxSpec: [cx,cy,cz, hx,hy,hz, hx2,hy2,hz2, hx3,hy3,hz3]
    const cx = boxSpec[0], cy = boxSpec[1], cz = boxSpec[2];
    const h1 = [boxSpec[3], boxSpec[4], boxSpec[5]];
    const h2 = [boxSpec[6], boxSpec[7], boxSpec[8]];
    const h3 = [boxSpec[9], boxSpec[10], boxSpec[11]];

    // Generate 8 corners of the oriented bounding box.
    const corners = [];
    for (let i = 0; i < 8; i++) {
        const s1 = (i & 1) ? 1 : -1;
        const s2 = (i & 2) ? 1 : -1;
        const s3 = (i & 4) ? 1 : -1;
        corners.push([
            cx + s1 * h1[0] + s2 * h2[0] + s3 * h3[0],
            cy + s1 * h1[1] + s2 * h2[1] + s3 * h3[1],
            cz + s1 * h1[2] + s2 * h2[2] + s3 * h3[2],
        ]);
    }

    // Find min & max extents, then convert to a bounding sphere.
    let minX = corners[0][0], maxX = corners[0][0];
    let minY = corners[0][1], maxY = corners[0][1];
    let minZ = corners[0][2], maxZ = corners[0][2];

    for (const [x, y, z] of corners) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
        if (z < minZ) minZ = z;
        if (z > maxZ) maxZ = z;
    }
    const midX = 0.5 * (minX + maxX);
    const midY = 0.5 * (minY + maxY);
    const midZ = 0.5 * (minZ + maxZ);
    const dx = maxX - minX;
    const dy = maxY - minY;
    const dz = maxZ - minZ;
    const radius = 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz);

    return new Sphere([midX, midY, midZ], radius);
}

/* ---------------------------------------------------------------------------
   4) BFS of 3D Tiles
   --------------------------------------------------------------------------- */
const parseQueue = new PQueue({ concurrency: 10 });

async function parseNode(node, regionSphere, baseURL, sessionRef, apiKey, results) {
    // Check boundingVolume intersection
    let intersects = false;

    if (node.boundingVolume?.box) {
        const sphere = obbToSphere(node.boundingVolume.box);
        if (regionSphere.intersects(sphere)) {
            intersects = true;
        }
    } else {
        // if there's no boundingVolume box, we have no choice but to assume it intersects
        intersects = true;
    }
    if (!intersects) return;

    // If node has children, skip this content (like LOD)
    if (node.children && node.children.length > 0) {
        for (const child of node.children) {
            await parseNode(child, regionSphere, baseURL, sessionRef, apiKey, results);
        }
        return;
    }

    // If no children, check for .glb
    if (node.content && node.content.uri) {
        const contentURL = new URL(node.content.uri, baseURL);
        const childSession = contentURL.searchParams.get('session');
        if (childSession) {
            sessionRef.value = childSession;
        }
        if (!contentURL.searchParams.has('key')) {
            contentURL.searchParams.set('key', apiKey);
        }
        if (sessionRef.value && !contentURL.searchParams.has('session')) {
            contentURL.searchParams.set('session', sessionRef.value);
        }
        const fullUrl = contentURL.toString();
        if (fullUrl.endsWith('.glb')) {
            results.push(fullUrl);
        } else {
            // If it's another tileset, recurse BFS
            parseQueue.add(() => fetchTileSet(fullUrl, regionSphere, sessionRef, apiKey, results));
        }
    }
}

async function fetchTileSet(tilesetUrl, regionSphere, sessionRef, apiKey, results) {
    const urlObj = new URL(tilesetUrl);
    if (!urlObj.searchParams.has('key')) {
        urlObj.searchParams.set('key', apiKey);
    }
    if (sessionRef.value && !urlObj.searchParams.has('session')) {
        urlObj.searchParams.set('session', sessionRef.value);
    }

    let resp;
    try {
        resp = await axiosInstance.get(urlObj.toString(), { responseType: 'json' });
    } catch (err) {
        console.warn(`[WARN] fetchTileSet: Could not fetch ${tilesetUrl}`, err);
        return;
    }
    if (resp.status !== 200) {
        console.warn(`[WARN] fetchTileSet: ${resp.status} => ${tilesetUrl}`);
        return;
    }
    const ctype = resp.headers['content-type'] || '';
    if (!ctype.includes('application/json')) {
        // If it's not JSON, it could be a glb or other resource
        results.push(urlObj.toString());
        return;
    }
    const data = resp.data;
    if (!data.root) {
        console.warn(`[WARN] No root in sub-tileset: ${tilesetUrl}`);
        return;
    }
    await parseNode(data.root, regionSphere, urlObj, sessionRef, apiKey, results);
}

/* ---------------------------------------------------------------------------
   5) Main script
   --------------------------------------------------------------------------- */
(async function main() {
    const argv = yargs(hideBin(process.argv))
        .option('key', { type: 'string', demandOption: true })
        .option('lat', { type: 'number', demandOption: true })
        .option('lng', { type: 'number', demandOption: true })
        .option('radius', { type: 'number', demandOption: true })
        .option('out', { type: 'string', demandOption: true })
        .option('origin', {
            type: 'array',
            default: null,
            describe: 'ECEF origin x y z (optional)',
        })
        .option('parallel', { type: 'number', default: 10 })
        .help()
        .argv;

    const { key, lat, lng, radius, out: outDir, origin, parallel } = argv;
    if (!fs.existsSync(outDir)) {
        fs.mkdirSync(outDir, { recursive: true });
    }

    // 1) Elevation from Google API
    let elevation = 0;
    try {
        elevation = await getElevation(key, lat, lng);
        console.log(`[INFO] Found ground elevation ~${elevation.toFixed(2)} m`);
    } catch (err) {
        console.warn(`[WARN] Elevation fetch failed => using 0. Error: ${err}`);
    }

    // 2) Prepare a region sphere in ECEF for culling
    function cartesianFromDegrees(lonDeg, latDeg, h = 0) {
        const a = 6378137.0;
        const f = 1 / 298.257223563;
        const e2 = f * (2 - f);
        const radLat = (latDeg * Math.PI) / 180;
        const radLon = (lonDeg * Math.PI) / 180;
        const sinLat = Math.sin(radLat);
        const cosLat = Math.cos(radLat);
        const N = a / Math.sqrt(1 - e2 * sinLat * sinLat);
        const x = (N + h) * cosLat * Math.cos(radLon);
        const y = (N + h) * cosLat * Math.sin(radLon);
        const z = (N * (1 - e2) + h) * sinLat;
        return [x, y, z];
    }
    const centerECEF = cartesianFromDegrees(lng, lat, elevation);
    const regionSphere = new Sphere(centerECEF, radius);

    // 3) Gather .glb URLs by BFS
    console.log(`[INFO] Gathering sub-tiles in bounding volume...`);
    const rootUrl = new URL('https://tile.googleapis.com/v1/3dtiles/root.json');
    rootUrl.searchParams.set('key', key);

    const glbResults = [];
    const sessionRef = { value: null };
    try {
        const resp = await axiosInstance.get(rootUrl.toString(), { responseType: 'json' });
        if (resp.status !== 200 || !resp.data?.root) {
            throw new Error(`root.json fetch error or missing root. code=${resp.status}`);
        }
        // BFS
        await parseNode(resp.data.root, regionSphere, rootUrl, sessionRef, key, glbResults);
        await parseQueue.onIdle();
    } catch (err) {
        console.error(`[ERROR] Could not gather 3D tiles: ${err}`);
        process.exit(1);
    }

    console.log(`[INFO] Found ${glbResults.length} .glb tile(s).`);
    if (glbResults.length === 0) {
        console.log("DOWNLOADED_TILES: []");
        process.exit(0);
    }

    // 4) If the user passed an --origin, we'll use that.
    // Otherwise, the first tile we process sets the origin.
    let globalOrigin = null;
    if (origin && origin.length === 3) {
        globalOrigin = origin.map(Number);
        console.log(`[INFO] Using user-specified origin => ${globalOrigin}`);
    }

    // 5) read + rotate + write each tile
    async function downloadRotateWrite(glbUrl, knownOrigin) {
        const io = await getGlobalIO();
        const hash = crypto.createHash('sha1').update(glbUrl).digest('hex');
        const outGlbPath = path.join(outDir, `${hash}.glb`);
        if (fs.existsSync(outGlbPath)) {
            console.log(`[SKIP] Already have ${outGlbPath}`);
            return { outPath: outGlbPath, finalOrigin: knownOrigin, localTranslation: [0, 0, 0] };
        }

        let doc;
        try {
            doc = await io.read(glbUrl);
        } catch (err) {
            throw new Error(`downloadRotateWrite: NodeIO.read() failed for ${glbUrl} => ${err}`);
        }

        // Apply rotation
        const { finalOrigin, firstNodeTranslation } = rotateDocument(doc, knownOrigin);

        // Save result
        const outBytes = await io.writeBinary(doc);
        fs.writeFileSync(outGlbPath, outBytes);

        return {
            outPath: outGlbPath,
            finalOrigin,
            localTranslation: firstNodeTranslation,
        };
    }

    // 6) Process the first tile if no global origin
    const downloadedTiles = [];
    if (!globalOrigin) {
        const firstTile = glbResults.shift();
        try {
            console.log(`[INFO] Processing FIRST tile => sets ORIGIN_TRANSLATION ...`);
            const { outPath, finalOrigin, localTranslation } = await downloadRotateWrite(firstTile, null);
            downloadedTiles.push(path.basename(outPath));
            globalOrigin = finalOrigin;
            console.log(`TILE_TRANSLATION ${path.basename(outPath)} ${JSON.stringify(localTranslation)}`);
        } catch (err) {
            console.error(`[ERROR] First tile failed:`, err);
            process.exit(1);
        }
    }

    // 7) Process remaining tiles in parallel
    console.log(`[INFO] Downloading & rotating the remaining ${glbResults.length} tile(s) in parallel...`);
    const downloadQueue = new PQueue({ concurrency: parallel });
    for (const url of glbResults) {
        downloadQueue.add(async () => {
            try {
                const { outPath, localTranslation } = await downloadRotateWrite(url, globalOrigin);
                downloadedTiles.push(path.basename(outPath));
                console.log(`TILE_TRANSLATION ${path.basename(outPath)} ${JSON.stringify(localTranslation)}`);
            } catch (err) {
                console.error(`[ERROR] downloadRotateWrite for ${url} =>`, err);
            }
        });
    }
    await downloadQueue.onIdle();

    // 8) Done
    console.log("DOWNLOADED_TILES:", JSON.stringify(downloadedTiles));
    process.exit(0);
})();
