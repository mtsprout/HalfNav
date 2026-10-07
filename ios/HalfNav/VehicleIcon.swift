import SwiftUI

/// Top-down car or navigation arrow, front pointing up. Same shapes as the Android vector icons
/// (48×48 design grid), drawn in code so they stay crisp at any size.
struct VehicleIcon: View {
    var vehicle: Vehicle

    var body: some View {
        Canvas { ctx, size in
            ctx.scaleBy(x: size.width / 48, y: size.height / 48)
            vehicle == .arrow ? drawArrow(ctx) : drawCar(ctx)
        }
    }

    private func rounded(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> Path {
        Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: r)
    }

    private func drawCar(_ ctx: GraphicsContext) {
        ctx.fill(rounded(11.5, 6.5, 26, 37, 6), with: .color(.black.opacity(0.2))) // shadow
        let body = rounded(11, 4, 26, 38, 6)
        ctx.fill(body, with: .color(Color(red: 0x1A / 255, green: 0x73 / 255, blue: 0xE8 / 255)))
        ctx.stroke(body, with: .color(.white), lineWidth: 2)
        ctx.fill(rounded(17, 19, 14, 13, 1.5), with: .color(Color(red: 0x4D / 255, green: 0x94 / 255, blue: 0xF0 / 255))) // roof
        let glass = Color(red: 0xD2 / 255, green: 0xE3 / 255, blue: 0xFC / 255)
        ctx.fill(Path { p in // windshield
            p.move(to: CGPoint(x: 16, y: 12.5)); p.addLine(to: CGPoint(x: 32, y: 12.5))
            p.addLine(to: CGPoint(x: 29.8, y: 18)); p.addLine(to: CGPoint(x: 18.2, y: 18)); p.closeSubpath()
        }, with: .color(glass))
        ctx.fill(Path { p in // rear window
            p.move(to: CGPoint(x: 18, y: 33.5)); p.addLine(to: CGPoint(x: 30, y: 33.5))
            p.addLine(to: CGPoint(x: 31.6, y: 37)); p.addLine(to: CGPoint(x: 16.4, y: 37)); p.closeSubpath()
        }, with: .color(glass))
        let lights = Color(red: 1, green: 0.96, blue: 0.62)
        ctx.fill(Path { p in
            p.move(to: CGPoint(x: 14.5, y: 6.5)); p.addLine(to: CGPoint(x: 18, y: 6.5))
            p.addLine(to: CGPoint(x: 18, y: 9)); p.addLine(to: CGPoint(x: 13.5, y: 9)); p.closeSubpath()
            p.move(to: CGPoint(x: 30, y: 6.5)); p.addLine(to: CGPoint(x: 33.5, y: 6.5))
            p.addLine(to: CGPoint(x: 34.5, y: 9)); p.addLine(to: CGPoint(x: 30, y: 9)); p.closeSubpath()
        }, with: .color(lights))
    }

    private func drawArrow(_ ctx: GraphicsContext) {
        ctx.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: 48, height: 48)), with: .color(.white))
        let arrow = Path { p in
            p.move(to: CGPoint(x: 24, y: 7)); p.addLine(to: CGPoint(x: 37, y: 39))
            p.addLine(to: CGPoint(x: 24, y: 32)); p.addLine(to: CGPoint(x: 11, y: 39)); p.closeSubpath()
        }
        ctx.fill(arrow, with: .color(Color(red: 0x1A / 255, green: 0x73 / 255, blue: 0xE8 / 255)))
        ctx.stroke(arrow, with: .color(.white), style: StrokeStyle(lineWidth: 1.5, lineJoin: .round))
    }

    /// The icon as an image, for drawing on the map itself.
    @MainActor
    static func image(_ vehicle: Vehicle) -> UIImage? {
        let r = ImageRenderer(content: VehicleIcon(vehicle: vehicle).frame(width: 40, height: 40))
        r.scale = 3
        return r.uiImage
    }
}
