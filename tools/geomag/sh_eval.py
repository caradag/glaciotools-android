import math, re
A, B, RE = 6378.137, 6356.7523142, 6371.2

def field(g, h, lat, lon, alt_km, nmax):
    """g,h: dict[(n,m)] Schmidt semi-normalised, nT. Returns X,Y,Z geodetic (nT)."""
    phi, lam = math.radians(lat), math.radians(lon)
    # geodetic -> geocentric
    a2, b2 = A*A, B*B
    sp, cp = math.sin(phi), math.cos(phi)
    rho = math.sqrt(a2*cp*cp + b2*sp*sp)
    r = math.sqrt(alt_km*alt_km + 2*alt_km*rho + (a2*a2*cp*cp + b2*b2*sp*sp)/(rho*rho))
    cd = (alt_km + rho)/r; sd = (a2 - b2)/rho*cp*sp/r
    st_ = sp*cd - cp*sd; ct = cp*cd + sp*sd      # sin/cos of geocentric latitude
    theta_c, theta_s = st_, ct                   # cos(colat)=sin(lat), sin(colat)=cos(lat)
    # Schmidt semi-normalised Legendre P(n,m) and dP/dtheta
    P = {(0,0):1.0}; dP = {(0,0):0.0}
    for n in range(1, nmax+1):
        for m in range(0, n+1):
            if n == m:
                k = math.sqrt(1 - 1/(2*m)) if m > 1 else 1.0
                P[n,m] = k*theta_s*P[n-1,m-1]
                dP[n,m] = k*(theta_s*dP[n-1,m-1] + theta_c*P[n-1,m-1])
            else:
                k1 = (2*n-1)/math.sqrt(n*n-m*m)
                k2 = math.sqrt(((n-1)**2 - m*m)/(n*n-m*m))
                P[n,m] = k1*theta_c*P[n-1,m] - (k2*P[n-2,m] if n-2 >= m else 0)
                dP[n,m] = k1*(theta_c*dP[n-1,m] - theta_s*P[n-1,m]) - (k2*dP[n-2,m] if n-2 >= m else 0)
    Br = Bt = Bp = 0.0
    for n in range(1, nmax+1):
        f = (RE/r)**(n+2)
        for m in range(0, n+1):
            gg, hh = g.get((n,m),0.0), h.get((n,m),0.0)
            c, s = math.cos(m*lam), math.sin(m*lam)
            t = gg*c + hh*s
            Br += f*(n+1)*t*P[n,m]
            Bt -= f*t*dP[n,m]
            Bp += f*m*(gg*s - hh*c)*P[n,m]/ (theta_s if abs(theta_s)>1e-10 else 1e-10)
    # geocentric (Bt southward) -> north/east/down
    Xc, Yc, Zc = -Bt, Bp, -Br
    X = Xc*cd + Zc*sd; Z = -Xc*sd + Zc*cd
    return X, Yc, Z

def decl(X, Y, Z):
    return math.degrees(math.atan2(Y, X))

def wmm_cof(path):
    L = open(path).read().split('\n'); epoch = float(L[0].split()[0])
    g, h, dg, dh = {}, {}, {}, {}
    for l in L[1:]:
        p = l.split()
        if len(p) < 6 or p[0].startswith('9999'): continue
        n, m = int(p[0]), int(p[1])
        g[n,m], h[n,m], dg[n,m], dh[n,m] = map(float, p[2:6])
    return epoch, g, h, dg, dh

def at_epoch(model, t):
    epoch, g, h, dg, dh = model
    dt = t - epoch
    return {k: g[k]+dg.get(k,0)*dt for k in g}, {k: h[k]+dh.get(k,0)*dt for k in h}

def aosp(path):
    s = open(path).read()
    def arr(name):
        body = re.search(name + r'\s*=\s*new float\[\]\[\]\s*\{(.*?)\};', s, re.S).group(1)
        rows = re.findall(r'\{([^{}]*)\}', body)
        return [[float(x.strip().rstrip('f')) for x in r.split(',') if x.strip()] for r in rows]
    G, H, DG, DH = (arr(n) for n in ('G_COEFF','H_COEFF','DELTA_G','DELTA_H'))
    m2 = re.search(r'setDate\((\d{4})', s) or re.search(r'GregorianCalendar\((\d{4}), (\d+), (\d+)\)', s)
    ep = float(m2.group(1))
    g, h, dg, dh = {}, {}, {}, {}
    for n in range(1, len(G)):
        for m in range(n+1):
            g[n,m], h[n,m], dg[n,m], dh[n,m] = G[n][m], H[n][m], DG[n][m], DH[n][m]
    return ep, g, h, dg, dh

def igrf(path, t):
    L = [l.split() for l in open(path) if l[:2] in ('g ','h ')]
    hdr = [l for l in open(path) if l.startswith('g/h')][0].split()
    years = [float(y) for y in hdr[3:-1]]  # last column is SV
    g, h = {}, {}
    i = max(k for k, y in enumerate(years) if y <= t) if t < years[-1] else len(years)-1
    for p in L:
        n, m = int(p[1]), int(p[2]); vals = [float(v) for v in p[3:]]
        if t >= years[-1]:
            v = vals[len(years)-1] + vals[-1]*(t-years[-1])
        else:
            y0, y1 = years[i], years[i+1]
            v = vals[i] + (vals[i+1]-vals[i])*(t-y0)/(y1-y0)
        (g if p[0]=='g' else h)[n,m] = v
    return g, h
