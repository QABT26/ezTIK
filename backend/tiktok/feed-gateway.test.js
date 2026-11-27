"use strict";
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {normalize, allowedVideoUrl, videoCandidates} = require("./feed-gateway");

test("rejects untrusted video urls and loopback", () => {
  for (const url of [
    "https://tiktok.com.evil.example/media", "http://www.tiktok.com/x",
    "https://127.0.0.1/video", "https://user@www.tiktok.com/x",
    "https://www.tiktok.com:444/x", "javascript:alert(1)"
  ]) assert.equal(allowedVideoUrl(url), false);
  assert.equal(allowedVideoUrl("https://www.tiktok.com/aweme/v1/play/?id=1"), true);
});

test("prefers proven aweme playback over prime CDN", () => {
  const video = {playAddr:"https://v16-webapp-prime.tiktok.com/a",
    bitrateInfo:[{PlayAddr:{UrlList:[
      "https://v16-webapp-prime.tiktok.com/b",
      "https://www.tiktok.com/aweme/v1/play/?item=10"
    ]}}]};
  assert.match(videoCandidates(video)[0], /aweme\/v1\/play/);
  const feed = normalize({itemList:[{id:"123456789012345678",desc:"Test",video}]});
  assert.equal(feed.itemList.length,1);
  assert.match(feed.itemList[0].video.playAddr,/aweme\/v1\/play/);
});

test("drops photo, untrusted URLs and duplicate IDs without leaking metadata", () => {
  const source = {itemList:[
    {id:"123456789012345678",desc:"OK",secret:"must-not-leak",
      video:{playAddr:"https://www.tiktok.com/aweme/v1/play/?id=1"}},
    {id:"123456789012345678",desc:"Duplicate",
      video:{playAddr:"https://www.tiktok.com/aweme/v1/play/?id=2"}},
    {id:"223456789012345678",imagePost:{images:[{}]},
      video:{playAddr:"https://www.tiktok.com/aweme/v1/play/?id=3"}},
    {id:"323456789012345678",
      video:{playAddr:"https://evil.example/x"}}
  ]};
  const clean=normalize(source);
  assert.equal(clean.itemList.length,1);
  assert.equal(clean.itemList[0].secret,undefined);
  assert.equal(JSON.stringify(clean).includes("must-not-leak"),false);
});
test("fails closed for empty feed", () => {
  assert.throws(()=>normalize({itemList:[]}),/no valid video/);
  assert.throws(()=>normalize({}),/itemList/);
});
