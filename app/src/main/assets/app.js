let lastCoords = null;

function showTab(name, btn) {
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.getElementById('page-' + name).classList.add('active');
    btn.classList.add('active');
    btn.scrollIntoView({ behavior: 'smooth', inline: 'center' });
}

function buildRows(obj, id) {
    const el = document.getElementById(id);
    if (!el || !obj) return;
    el.innerHTML = Object.entries(obj).map(([k, v]) => {
        const label = k.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase());
        let val = v;
        if (v === true) val = "Yes";
        else if (v === false) val = "No";
        return `<div class="row"><span class="label">${label}</span><span class="value">${val}</span></div>`;
    }).join('');
}

function bridge(method, ...args) {
    try {
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge[method]) {
            const result = AndroidBridge[method](...args);
            return result ? JSON.parse(result) : null;
        }
    } catch(e) { console.error('Bridge Error:', method, e); }
    return null;
}

function openCamera(facing) {
    if (typeof AndroidBridge !== 'undefined') {
        if (facing === 'front') AndroidBridge.openFrontCamera();
        else AndroidBridge.openBackCamera();
    } else {
        alert('Camera bridge only available on device');
    }
}

function openGallery() {
    if (typeof AndroidBridge !== 'undefined') {
        AndroidBridge.openGallery();
    } else {
        alert('Gallery bridge only available on device');
    }
}

function loadDevice() {
    const d = bridge('getDeviceInfo');
    if (d) {
        buildRows(d, 'device-rows');
        document.getElementById('header-device-name').textContent = d.manufacturer + " " + d.model;
    }
    const l = bridge('getLocaleInfo');
    if (l) buildRows(l, 'locale-rows');
}

function loadNetwork() {
    const n = bridge('getNetworkInfo');
    if (n) buildRows(n, 'network-rows');

    const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    buildRows({
        'Online': navigator.onLine,
        'Effective Type': conn?.effectiveType ?? 'Unknown',
        'Downlink': conn ? conn.downlink + ' Mbps' : 'Unknown',
        'Save Data': conn?.saveData ?? false
    }, 'web-network-rows');
}

function loadCpu() {
    const c = bridge('getCpuInfo');
    if (c) buildRows(c, 'cpu-rows');
}

function loadAudio() {
    const a = bridge('getAudioInfo');
    if (a) buildRows(a, 'audio-rows');
}

function startSpeechRecognition() {
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.startSpeechToText) {
        AndroidBridge.startSpeechToText();
    } else {
        alert('Speech Recognition only available on device');
    }
}

window.onSpeechRecognized = function(text) {
    document.getElementById('speech-result').innerText = text || "(No speech detected)";
};

function loadBiometric() {
    const b = bridge('getBiometricInfo');
    if (b) buildRows(b, 'biometric-rows');
}

function testBiometric() {
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.authenticate) {
        document.getElementById('bio-status').innerText = "Authenticating...";
        AndroidBridge.authenticate();
    } else {
        alert('Biometric auth only available on device');
    }
}

window.onBiometricResult = function(result) {
    document.getElementById('bio-status').innerText = result;
    if (result.includes('Success')) {
        document.getElementById('secret-content').style.display = 'block';
        document.getElementById('btn-auth').style.display = 'none';
    }
};

function loadCamera() {
    const c = bridge('getCameraInfo');
    if (c) buildRows(c, 'camera-rows');
}

function loadGpsStatus() {
    const g = bridge('getGpsStatus');
    if (g) buildRows(g, 'gps-status-rows');
}

function fetchLocation() {
    const d = document.getElementById('loc-display');
    d.innerHTML = '<div class="loading">Fetching GPS...</div>';
    document.getElementById('btn-map').style.display = 'none';

    if (!navigator.geolocation) {
        d.innerHTML = 'Geolocation not supported';
        return;
    }

    navigator.geolocation.getCurrentPosition(
        pos => {
            const lat = pos.coords.latitude;
            const lng = pos.coords.longitude;
            lastCoords = { lat, lng };
            let address = "Looking up address...";

            try {
                if (typeof AndroidBridge !== 'undefined' && AndroidBridge.getAddress) {
                    address = AndroidBridge.getAddress(lat, lng);
                }
            } catch(e) { address = "Address unavailable"; }

            d.innerHTML = `
                <div class="coord">${lat.toFixed(5)}, ${lng.toFixed(5)}</div>
                <div style="font-size: 13px; color: #666; padding: 0 10px;">${address}</div>
            `;
            document.getElementById('btn-map').style.display = 'block';
        },
        err => { d.innerHTML = 'Error: ' + err.message; },
        { enableHighAccuracy: true, timeout: 10000 }
    );
}

function viewOnMap() {
    if (lastCoords && typeof AndroidBridge !== 'undefined') {
        AndroidBridge.openMaps(lastCoords.lat, lastCoords.lng);
    }
}

window.onload = () => {
    loadDevice();
    loadNetwork();
    loadCpu();
    loadAudio();
    loadBiometric();
    loadCamera();
    loadGpsStatus();
};