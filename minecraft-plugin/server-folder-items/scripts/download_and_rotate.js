#!/usr/bin/env node
/* eslint-disable no-console */
import fs from 'fs';
import path from 'path';
import yargs from 'yargs/yargs';
import { hideBin } from 'yargs/helpers';
import PQueue from 'p-queue';
import crypto from 'crypto';
import http from 'http';
import https from 'https';
import axios from 'axios';
import { quat, mat4, vec3 } from 'gl-matrix';
import { NodeIO } from '@gltf-transform/core';
import { KHRDracoMeshCompression, KHRMaterialsUnlit } from '@gltf-transform/extensions';
import draco3d from 'draco3dgltf';

// ──────────────────────────────────────────────────────────────────────────────
// Keep-alive agents & Axios instance
// ──────────────────────────────────────────────────────────────────────────────
const httpAgent = new http.Agent({ keepAlive: true });
const httpsAgent = new https.Agent({ keepAlive: true });
const axiosInstance = axios.create({ httpAgent, httpsAgent, timeout: 30000 });

// ──────────────────────────────────────────────────────────────────────────────
// Global NodeIO for reading from URLs (no temp files)
// ──────────────────────────────────────────────────────────────────────────────
let GLOBAL_IO = null;
async function getGlobalIO() {
  if (GLOBAL_IO) return GLOBAL_IO;
  const io = new NodeIO(fetch).setAllowNetwork(true);
  const dracoDecoder = await draco3d.createDecoderModule();
  const dracoEncoder = await draco3d.createEncoderModule();
  io
    .registerExtensions([KHRDracoMeshCompression, KHRMaterialsUnlit])
    .registerDependencies({
      'draco3d.decoder': dracoDecoder,
      'draco3d.encoder': dracoEncoder,
    });
  GLOBAL_IO = io;
  return io;
}

// ──────────────────────────────────────────────────────────────────────────────
// "normalize" for up-vector computations
// ──────────────────────────────────────────────────────────────────────────────
function normalize(v) {
  const length = Math.hypot(v[0], v[1], v[2]);
  return length > 0 ? v.map(val => val / length) : v;
}

/**
 * rotateGlbDocument():
 *
 * Takes a glTF Document, plus:
 *   - originTranslation: The global origin (or null if not set).
 *   - tileOriginalTranslation: The tile’s absolute position prior to any rotation
 *     (either from extras if local, or from the node if new).
 *
 * If originTranslation is null, we adopt tileOriginalTranslation as the global origin and
 * log "ORIGIN_TRANSLATION ...".
 *
 * Then we compute the tile's relative translation = tileOriginalTranslation - originTranslation,
 * rotate it so that the tile’s "up" vector (derived from tileOriginalTranslation) is [0,1,0],
 * and apply that rotation to the node’s translation & vertices.
 *
 * Returns { originTranslation, firstNodeFinalTranslation } for logging & updates.
 */
async function rotateGlbDocument(doc, originTranslation, tileOriginalTranslation) {
  const root = doc.getRoot();
  const nodes = root.listNodes();

  let firstNodeFinalTranslation = null;

  // If we have no global origin yet, adopt the tile's original translation.
  if (!originTranslation && tileOriginalTranslation) {
    originTranslation = tileOriginalTranslation.slice();
    console.log(`ORIGIN_TRANSLATION ${JSON.stringify(originTranslation)}`);
  }
  // Now we do the actual rotation. We'll apply the same transform to the *first node*
  // that has a translation. If there are multiple nodes, you can adapt similarly,
  // but the script historically only focuses on the first node with translation.
  for (const node of nodes) {
    const nodeTranslation = node.getTranslation();
    if (!nodeTranslation) continue;

    // We do NOT trust the node's existing translation if the tile is local, because
    // it may have been rotated already. Instead, we use tileOriginalTranslation
    // as the absolute position for rotation.
    // We'll only do that for the "first" node that actually had a translation.
    // If there's more than one node, you'd adapt similarly or keep the simplified approach.
    const absTranslation = tileOriginalTranslation
      ? tileOriginalTranslation
      : nodeTranslation; // fallback if new tile & first node

    // Compute (relative) = absTranslation - origin
    if (!originTranslation) {
      // If somehow still no origin, skip
      continue;
    }
    const relativeTranslation = [
      absTranslation[0] - originTranslation[0],
      absTranslation[1] - originTranslation[1],
      absTranslation[2] - originTranslation[2],
    ];

    // The tile's "up" is the direction of absTranslation
    const upVec = normalize(absTranslation);
    const desiredUp = [0, 1, 0];

    // Build rotation from upVec => desiredUp
    const rotationQuaternion = quat.create();
    quat.rotationTo(rotationQuaternion, upVec, desiredUp);
    const rotationMatrix = mat4.fromQuat([], rotationQuaternion);

    // Rotate that relative translation
    const rotatedTranslation = vec3.transformMat4([], relativeTranslation, rotationMatrix);

    // Rotate the geometry's vertices
    const mesh = node.getMesh();
    if (mesh) {
      for (const prim of mesh.listPrimitives()) {
        const positionAccessor = prim.getAttribute('POSITION');
        if (!positionAccessor) continue;
        const arr = positionAccessor.getArray();
        const vertexCount = positionAccessor.getCount();
        for (let i = 0; i < vertexCount; i++) {
          const x = arr[i * 3];
          const y = arr[i * 3 + 1];
          const z = arr[i * 3 + 2];
          const rotatedVertex = vec3.transformMat4([], [x, y, z], rotationMatrix);
          arr[i * 3 + 0] = rotatedVertex[0];
          arr[i * 3 + 1] = rotatedVertex[1];
          arr[i * 3 + 2] = rotatedVertex[2];
        }
        positionAccessor.setArray(arr);
      }
    }
    // Zero out the node’s rotation and set the final translation
    node.setRotation([0, 0, 0, 1]);
    node.setTranslation(rotatedTranslation);

    if (!firstNodeFinalTranslation) {
      firstNodeFinalTranslation = rotatedTranslation;
    }
    // For simplicity, break after rotating the first node that had a translation
    break;
  }

  return { originTranslation, firstNodeFinalTranslation };
}

// ──────────────────────────────────────────────────────────────────────────────
// Google Elevation API
// ──────────────────────────────────────────────────────────────────────────────
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

// ──────────────────────────────────────────────────────────────────────────────
// ECEF center => bounding-sphere culling
// ──────────────────────────────────────────────────────────────────────────────
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

// ──────────────────────────────────────────────────────────────────────────────
// Simple bounding sphere
// ──────────────────────────────────────────────────────────────────────────────
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
    return dist < this.radius + other.radius;
  }
}

// Convert boundingVolume.box => approximate sphere
function obbToSphere(boxSpec) {
  // [cx, cy, cz, h1x, h1y, h1z, h2x, h2y, h2z, h3x, h3y, h3z]
  const cx = boxSpec[0], cy = boxSpec[1], cz = boxSpec[2];
  const h1 = [boxSpec[3], boxSpec[4], boxSpec[5]];
  const h2 = [boxSpec[6], boxSpec[7], boxSpec[8]];
  const h3 = [boxSpec[9], boxSpec[10], boxSpec[11]];
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

// ──────────────────────────────────────────────────────────────────────────────
// BFS of 3D Tiles to gather .glb URLs
// ──────────────────────────────────────────────────────────────────────────────
const parseQueue = new PQueue({ concurrency: 10 });

async function parseNode(node, regionSphere, baseURL, sessionRef, apiKey, results) {
  let intersects = false;
  if (node.boundingVolume?.box) {
    const sphere = obbToSphere(node.boundingVolume.box);
    if (regionSphere.intersects(sphere)) {
      intersects = true;
    }
  } else {
    intersects = true;
  }
  if (!intersects) return;

  if (Array.isArray(node.children) && node.children.length > 0) {
    for (const child of node.children) {
      await parseNode(child, regionSphere, baseURL, sessionRef, apiKey, results);
    }
    return;
  }

  if (node.content && node.content.uri) {
    const contentURL = new URL(node.content.uri, baseURL);
    // If session param present, store it
    const childSession = contentURL.searchParams.get('session');
    if (childSession) sessionRef.value = childSession;
    // Ensure key & session are appended
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
    console.warn(`[WARN] fetchTileSet: HTTP ${resp.status} => ${tilesetUrl}`);
    return;
  }
  const ctype = resp.headers['content-type'] || '';
  if (!ctype.includes('application/json')) {
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

// A stable tile ID from the URL => hashed filename
function tileIdentifierFromUrl(fullUrl) {
  const urlObj = new URL(fullUrl);
  urlObj.searchParams.delete('session');
  urlObj.searchParams.delete('key');
  return urlObj.pathname + urlObj.search;
}

// ──────────────────────────────────────────────────────────────────────────────
// downloadTransformWrite():
//   - If the tile .glb already exists locally, read from disk
//       => retrieve the "originalTranslation" from doc.getRoot().getExtras()
//       => use that as the tile's absolute position
//   - Else, read from the network
//       => find the first node's translation => store in extras as originalTranslation
//   - Then rotate the doc using rotateGlbDocument() with that original translation
//   - If new, write the doc to disk
//   - Log the final rotation, plus copyright
// ──────────────────────────────────────────────────────────────────────────────
async function downloadTransformWrite(glbUrl, outDir, originTranslationGlobal) {
  const tileId = tileIdentifierFromUrl(glbUrl);
  const hash = crypto.createHash('sha1').update(tileId).digest('hex');
  const outGlbPath = path.join(outDir, `${hash}.glb`);

  const io = await getGlobalIO();
  let doc;
  let tileOriginalTranslation = null;
  let tileAlreadyLocal = fs.existsSync(outGlbPath);

  if (tileAlreadyLocal) {
    console.warn(`[SKIP] ${outGlbPath} already exists. Using local file for transform.`);
    // read from disk
    try {
      doc = await io.read(outGlbPath);
    } catch (err) {
      console.error(`[ERROR] Could not read existing GLB from ${outGlbPath}`, err);
      return { updatedOrigin: originTranslationGlobal, fileName: path.basename(outGlbPath) };
    }
    // fetch original from extras
    const extras = doc.getRoot().getExtras();
    if (extras && Array.isArray(extras.originalTranslation)) {
      tileOriginalTranslation = extras.originalTranslation.slice();
      console.log(`[INFO] Recovered original translation: ${JSON.stringify(tileOriginalTranslation)}`);
    } else {
      console.warn('[WARN] Local GLB has no extras.originalTranslation. Fallback to node translation.');
      // fallback: just use the first node's translation
      const node = doc.getRoot().listNodes()[0];
      if (node) {
        tileOriginalTranslation = node.getTranslation().slice();
      }
    }
  } else {
    // read from network
    try {
      doc = await io.read(glbUrl);
    } catch (err) {
      console.error(`[ERROR] Could not read GLB from ${glbUrl}`, err);
      return { updatedOrigin: originTranslationGlobal, fileName: null };
    }

    // find first node that has a translation => store as original
    const nodes = doc.getRoot().listNodes();
    for (const node of nodes) {
      const t = node.getTranslation();
      if (t) {
        tileOriginalTranslation = t.slice();
        break;
      }
    }
    // store that in extras
    doc.getRoot().setExtras({ originalTranslation: tileOriginalTranslation });
  }

  // Rotate the doc using the tile's original translation as absolute reference
  const { originTranslation, firstNodeFinalTranslation } = await rotateGlbDocument(
    doc,
    originTranslationGlobal,
    tileOriginalTranslation
  );

  // if new, write out
  if (!tileAlreadyLocal) {
    // ensure we keep the originalTranslation in extras
    doc.getRoot().setExtras({ originalTranslation: tileOriginalTranslation });
    await io.write(outGlbPath, doc);
    console.log(`[INFO] Wrote new tile => ${path.basename(outGlbPath)}`);
  }

  // Log the asset's copyright
  const asset = doc.getRoot().getAsset();
  console.log(`ASSET_COPYRIGHT ${path.basename(outGlbPath)} ${asset?.copyright || ''}`);

  // Log tile final translation
  if (firstNodeFinalTranslation) {
    console.log(
      `TILE_TRANSLATION ${path.basename(outGlbPath)} ${JSON.stringify(firstNodeFinalTranslation)}`
    );
  } else {
    console.log(`TILE_TRANSLATION ${path.basename(outGlbPath)} [0,0,0]`);
  }
  return { updatedOrigin: originTranslation, fileName: path.basename(outGlbPath) };
}

// ──────────────────────────────────────────────────────────────────────────────
// Main
// ──────────────────────────────────────────────────────────────────────────────
(async function main() {
  const argv = yargs(hideBin(process.argv))
    .option('key', { type: 'string', demandOption: true })
    .option('lat', { type: 'number', demandOption: true })
    .option('lng', { type: 'number', demandOption: true })
    .option('radius', { type: 'number', demandOption: true })
    .option('out', { type: 'string', demandOption: true })
    .option('parallel', { type: 'number', default: 10 })
    .option('origin', {
      type: 'array',
      description: 'Optional origin as three numbers: x y z. E.g. --origin 3383551.7245894047 2624125.992463888 -4722209.096214473',
    })
    .help()
    .argv;

  const { key, lat, lng, radius, out: outDir, parallel, origin } = argv;

  if (!fs.existsSync(outDir)) {
    fs.mkdirSync(outDir, { recursive: true });
  }

  // Parse user-supplied origin (three separate numbers)
  let originTranslationGlobal = null;
  if (origin) {
    if (!Array.isArray(origin) || origin.length !== 3) {
      console.error(`[ERROR] --origin requires exactly three numeric values: x y z`);
      process.exit(1);
    }
    originTranslationGlobal = origin.map(Number);
    if (originTranslationGlobal.some(isNaN)) {
      console.error(`[ERROR] --origin values must be valid numbers.`);
      process.exit(1);
    }
    console.log(`[INFO] Using user-provided origin: ${JSON.stringify(originTranslationGlobal)}`);
  }

  // Try to get ground elevation
  let elevation = 0;
  try {
    elevation = await getElevation(key, lat, lng);
    console.log(`[INFO] Found ground elevation ~${elevation.toFixed(2)} m`);
  } catch (err) {
    console.warn(`[WARN] Elevation fetch failed => using 0. Error: ${err}`);
  }

  // region sphere
  const centerECEF = cartesianFromDegrees(lng, lat, elevation);
  const regionSphere = new Sphere(centerECEF, radius);

  // BFS gather
  console.log('[INFO] Gathering sub-tiles in bounding volume...');
  const rootUrl = new URL('https://tile.googleapis.com/v1/3dtiles/root.json');
  rootUrl.searchParams.set('key', key);

  const glbResults = [];
  const sessionRef = { value: null };

  try {
    const resp = await axiosInstance.get(rootUrl.toString(), { responseType: 'json' });
    if (resp.status !== 200 || !resp.data?.root) {
      throw new Error(`root.json fetch error or missing root. code=${resp.status}`);
    }
    await parseNode(resp.data.root, regionSphere, rootUrl, sessionRef, key, glbResults);
    await parseQueue.onIdle();
  } catch (err) {
    console.error(`[ERROR] Could not gather 3D tiles: ${err}`);
    process.exit(1);
  }

  console.log(`[INFO] Found ${glbResults.length} .glb tile(s).`);
  if (glbResults.length === 0) {
    console.log('DOWNLOADED_TILES: []');
    process.exit(0);
  }

  const downloadedTiles = [];

  // If we do not have a user-supplied origin, adopt the first tile's originalTranslation in serial
  if (!originTranslationGlobal && glbResults.length > 0) {
    const firstTile = glbResults.shift();
    const result = await downloadTransformWrite(firstTile, outDir, originTranslationGlobal);
    originTranslationGlobal = result.updatedOrigin;
    if (result.fileName) downloadedTiles.push(result.fileName);
  }

  // Then do the rest in parallel
  const downloadQueue = new PQueue({ concurrency: parallel });
  for (const url of glbResults) {
    downloadQueue.add(async () => {
      const result = await downloadTransformWrite(url, outDir, originTranslationGlobal);
      originTranslationGlobal = result.updatedOrigin;
      if (result.fileName) downloadedTiles.push(result.fileName);
    });
  }
  await downloadQueue.onIdle();

  console.log('DOWNLOADED_TILES:', JSON.stringify(downloadedTiles));
  process.exit(0);
})();
