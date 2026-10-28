// Host test harness for the AndSlice engine. It drives the exact code the
// Android app uses, so slicing can be verified on a desktop or in CI:
//
//   andslice_cli --bundle Kobra3.ini [--printer P] [--print P] [--filament F]...
//                [--extruder OBJECT=N]... [--set key=value]... -o out.gcode model.stl [model2.stl ...]
//   andslice_cli --bundle Kobra3.ini --list [--printer P]
//
// --vendor adds more config bundles (PrusaSlicer vendor profiles), loaded after --bundle.
// --save TYPE:NAME saves the selected TYPE preset with the --set values as a user preset.
//
// --filament can be repeated, once per extruder (ACE slot). --extruder assigns
// the 1-based extruder N to the object with the 0-based index OBJECT.
// --paint OBJECT=MODE:STATE[:OPTION]... paints the object from straight above its center, as a
// touch would: MODE is supports or colors, STATE the support type or extruder (0 erases), with
// an 8 mm brush. Options: fill (smart fill), heightN (a band N mm high), below (paint from
// under the bed), overhangsN (every overhang tilted less than N degrees, instead of a touch).
//
// --project IN.3mf opens a 3MF project as the plate (before the models are added), and
// --save-project OUT.3mf saves the plate with the settings once the models are added and painted.
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include "Engine.hpp"

using andslice::Engine;
using nlohmann::json;

static int usage()
{
    std::cerr << "usage: andslice_cli --bundle FILE [--vendor FILE]... [--printer NAME] [--print NAME] [--filament NAME]...\n"
                 "                    [--extruder OBJECT=N]... [--set KEY=VALUE]... (--list | -o OUT.gcode MODEL...)\n";
    return 2;
}

int main(int argc, char** argv)
{
    std::string bundle, printer, print, output;
    std::vector<std::string> vendors;
    std::string save, project, save_project;
    std::vector<std::string> filaments;
    std::vector<std::pair<std::string, std::string>> overrides;
    std::vector<std::pair<int, int>> extruders;
    std::vector<std::pair<int, std::string>> paints;
    std::vector<std::string> models;
    bool list = false;

    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (a == "--bundle")        bundle = next();
        else if (a == "--vendor")   vendors.push_back(next());
        else if (a == "--save")     save = next();
        else if (a == "--project")  project = next();
        else if (a == "--save-project") save_project = next();
        else if (a == "--printer")  printer = next();
        else if (a == "--print")    print = next();
        else if (a == "--filament") filaments.push_back(next());
        else if (a == "--extruder") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            extruders.emplace_back(std::stoi(kv.substr(0, eq)), std::stoi(kv.substr(eq + 1)));
        }
        else if (a == "--paint") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            paints.emplace_back(std::stoi(kv.substr(0, eq)), kv.substr(eq + 1));
        }
        else if (a == "-o")         output = next();
        else if (a == "--list")     list = true;
        else if (a == "--set") {
            const std::string kv = next();
            const size_t eq = kv.find('=');
            if (eq == std::string::npos)
                return usage();
            overrides.emplace_back(kv.substr(0, eq), kv.substr(eq + 1));
        } else if (!a.empty() && a[0] == '-')
            return usage();
        else
            models.push_back(a);
    }
    if (bundle.empty() || (!list && save.empty() && save_project.empty() && (output.empty() || (models.empty() && project.empty()))))
        return usage();

    try {
        const auto data_dir = boost::filesystem::temp_directory_path() / "andslice-cli";
        Engine& engine = Engine::get();
        std::vector<std::string> bundles{ bundle };
        bundles.insert(bundles.end(), vendors.begin(), vendors.end());
        engine.init(data_dir.string(), data_dir.string(), bundles);

        const json presets = json::parse(engine.presets_json(printer));
        if (printer.empty())  printer  = presets["printer"];
        if (print.empty())    print    = presets["default_print"];
        if (filaments.empty()) filaments.push_back(presets["default_filament"]);

        if (!save.empty()) {
            const size_t colon = save.find(':');
            if (colon == std::string::npos)
                return usage();
            std::cout << "saved " << engine.save_preset(save.substr(0, colon), save.substr(colon + 1), printer, print,
                                                         filaments, overrides) << "\n";
            if (!list)
                return 0;
        }
        if (list) {
            std::cout << presets.dump(2) << "\n";
            const json defs = json::parse(engine.option_defs_json());
            std::cout << "option defs: print " << defs["print"].size() << ", filament " << defs["filament"].size()
                      << ", printer " << defs["printer"].size() << "\n";
            return 0;
        }

        engine.set_printer(printer);
        // Leave room for brims when placing several objects, as the app does.
        for (const auto& [key, value] : overrides)
            if (key == "brim_width")
                engine.set_arrange_distance(6.f + 2.f * std::stof(value));
        // Multi-material printers get a prime tower in the back right corner, as in the app.
        if (presets["extruders"].get<int>() > 1) {
            andslice::WipeTowerShape tower;
            tower.visible = true;
            tower.x = 190.f;
            tower.y = 215.f;
            tower.width = 45.f;
            tower.depth = 20.f;
            tower.brim = 5.f;
            engine.set_wipe_tower(tower);
        }
        if (!project.empty()) {
            json opened = json::parse(engine.open_project(project));
            const json& config = opened["config"];
            std::cout << "project " << project << ": " << opened["ids"].dump() << ", "
                      << (config.is_object() ? config.size() : 0) << " settings";
            if (config.is_object())
                for (const char* key : { "printer_settings_id", "print_settings_id", "filament_settings_id", "fill_density", "filament_colour" })
                    if (config.contains(key))
                        std::cout << ", " << key << "=" << config[key].get<std::string>();
            std::cout << "\n";
        }
        for (const std::string& m : models)
            std::cout << "loaded " << m << ": " << engine.add_model(m, "") << "\n";
        const json objects = json::parse(engine.objects_json());
        for (const auto& [index, extruder] : extruders)
            if (index >= 0 && index < int(objects.size()))
                engine.set_extruder(objects[index]["id"].get<uint64_t>(), -1, extruder);
        for (const auto& [index, spec] : paints) {
            if (index < 0 || index >= int(objects.size()))
                return usage();
            const json& o = objects[index];
            const int mode  = spec.rfind("colors", 0) == 0 ? andslice::PaintColors : andslice::PaintSupports;
            const size_t colon = spec.find(':');
            const int state = colon == std::string::npos ? 1 : std::stoi(spec.substr(colon + 1));
            auto number_after = [&spec](const std::string& tag) {
                const size_t at = spec.find(":" + tag);
                return at == std::string::npos ? 0.f : std::stof(spec.substr(at + tag.size() + 1));
            };
            andslice::PaintParams params;
            params.state        = state;
            params.radius       = 8.f;
            params.height_range = number_after("height");
            params.tool = spec.find(":fill") != std::string::npos ? andslice::PaintFill :
                          params.height_range > 0.f              ? andslice::PaintHeightRange : andslice::PaintBrush;
            const float overhangs = number_after("overhangs");
            // :below looks up at the object from under the bed.
            const bool  below     = spec.find(":below") != std::string::npos;
            const float camera[3] = { o["offset"][0].get<float>(), o["offset"][1].get<float>(), below ? -500.f : 500.f };
            const float dir[3]    = { 0.f, 0.f, below ? 1.f : -1.f };
            engine.paint_begin(o["id"].get<uint64_t>(), mode);
            const bool hit = overhangs > 0.f ? engine.paint_overhangs(state, overhangs) : engine.paint_at(camera, dir, params, false);
            engine.paint_commit();
            std::cout << "paint " << spec << (hit ? " hit: " : " missed: ") << engine.paint_state_json() << "\n";
            engine.paint_end();
        }
        std::cout << "plate: " << engine.objects_json() << "\n";

        andslice::SliceRequest req{ printer, print, filaments, overrides, output };
        if (!save_project.empty()) {
            engine.save_project(save_project, req);
            std::cout << "saved project " << save_project << " (" << boost::filesystem::file_size(save_project) << " bytes)\n";
            if (output.empty())
                return 0;
        }
        const std::string result = engine.slice(req, [](int percent, const std::string& msg) {
            std::cout << percent << "% " << msg << "\n";
        });
        json r = json::parse(result);
        if (r.contains("layers"))
            r["layers"] = r["layers"].size(); // keep the output short
        std::cout << r.dump(2) << "\n";
        return r.value("ok", false) ? 0 : 1;
    } catch (const std::exception& ex) {
        std::cerr << "error: " << ex.what() << "\n";
        return 1;
    }
}
